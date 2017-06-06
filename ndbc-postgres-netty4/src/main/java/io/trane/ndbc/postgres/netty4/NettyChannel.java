package io.trane.ndbc.postgres.netty4;

import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.trane.future.Future;
import io.trane.future.Promise;
import io.trane.ndbc.proto.ClientMessage;
import io.trane.ndbc.proto.ServerMessage;

final class NettyChannel extends SimpleChannelInboundHandler<ServerMessage> implements io.trane.ndbc.proto.Channel {

  private Promise<ChannelHandlerContext> ctx = Promise.apply();
  private final AtomicReference<Promise<ServerMessage>> nextMessagePromise = new AtomicReference<>(null);

  @Override
  public final void channelActive(final ChannelHandlerContext ctx) throws Exception {
    this.ctx.setValue(ctx);
    super.channelActive(ctx);
  }

  @Override
  public final void channelInactive(final ChannelHandlerContext ctx) throws Exception {
    this.ctx = Promise.apply();
    this.ctx.setException(new IllegalStateException("Channel inactive."));
    super.channelInactive(ctx);
  }

  @Override
  protected final void channelRead0(final ChannelHandlerContext ctx, final ServerMessage msg) throws Exception {
    final Promise<ServerMessage> p = nextMessagePromise.get();
    if (p == null)
      throw new IllegalStateException("Unexpected server message: " + msg);
    if (!nextMessagePromise.compareAndSet(p, null))
      throw new IllegalStateException("Invalid `nextMessagePromise` state: " + nextMessagePromise.get());
    p.setValue(msg);
  }

  @Override
  public final Future<ServerMessage> receive() {
    return ctx.flatMap(c -> {
      final Promise<ServerMessage> p = Promise.apply();
      if (nextMessagePromise.compareAndSet(null, p)) {
        c.read();
        return p;
      } else
        return Future.exception(new IllegalStateException("Previous `receive` still pending."));
    });
  }

  @Override
  public final Future<Void> send(final ClientMessage msg) {
    return ctx.flatMap(c -> {
      final Promise<Void> p = Promise.apply();
      c.writeAndFlush(msg).addListener(future -> p.become(Future.VOID));
      return p;
    });
  }

  @Override
  public final Future<Void> close() {
    return ctx.flatMap(c -> {
      final Promise<Void> p = Promise.apply();
      c.close().addListener(future -> {
        ctx = Promise.apply();
        ctx.setException(new IllegalStateException("Channel closed."));
        p.become(Future.VOID);
      });
      return p;
    });
  }
}