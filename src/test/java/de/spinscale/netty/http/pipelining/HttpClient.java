package de.spinscale.netty.http.pipelining;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaders.Names.HOST;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpClient implements AutoCloseable {

    private final Bootstrap clientBootstrap;

    public HttpClient() {
        clientBootstrap = new Bootstrap().channel(NioSocketChannel.class).group(new NioEventLoopGroup());
    }

    public synchronized Collection<FullHttpResponse> sendRequests(SocketAddress remoteAddress, String... uris) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(uris.length);
        final Collection<FullHttpResponse> content = Collections.synchronizedList(new ArrayList<FullHttpResponse>(uris.length));

        clientBootstrap.handler(new CountDownLatchHandler(latch, content));

        ChannelFuture channelFuture = null;
        try {
            channelFuture = clientBootstrap.connect(remoteAddress);
            channelFuture.await(1000);

            for (int i = 0; i < uris.length; i++) {
                final HttpRequest httpRequest = new DefaultFullHttpRequest(HTTP_1_1, GET, uris[i]);
                httpRequest.headers().add(HOST, "localhost");
                httpRequest.headers().add("X-Counting-Id", String.valueOf(i));
                ChannelFuture writeAndFlushFuture = channelFuture.channel().writeAndFlush(httpRequest);
                writeAndFlushFuture.syncUninterruptibly();
            }
            latch.await(5, TimeUnit.SECONDS);

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (channelFuture != null) {
                channelFuture.channel().close();
            }
        }

        return content;
    }

    public void close() {
        clientBootstrap.group().shutdownGracefully().awaitUninterruptibly();
    }

    /**
     * helper factory which adds returned data to a list and uses a count down latch to decide when done
     */
    public static class CountDownLatchHandler extends ChannelInitializer<SocketChannel> {
        private final CountDownLatch latch;
        private final Collection<FullHttpResponse> content;

        public CountDownLatchHandler(CountDownLatch latch, Collection<FullHttpResponse> content) {
            this.latch = latch;
            this.content = content;
        }

        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
            ch.pipeline().addLast(new HttpRequestEncoder());
            ch.pipeline().addLast(new HttpResponseDecoder());
            ch.pipeline().addLast(new HttpObjectAggregator(8192));
            ch.pipeline().addLast(new SimpleChannelInboundHandler<HttpObject>() {

                @Override
                protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
                    FullHttpResponse response = (FullHttpResponse) msg;
                    content.add(response.copy());
                    latch.countDown();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    cause.printStackTrace();
                    latch.countDown();
                    ctx.close();
                }
            });
        }
    }


}
