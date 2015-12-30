package de.spinscale.netty.http.pipelining;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.LastHttpContent;

import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Implements HTTP pipelining ordering, ensuring that responses are completely served in the same order as their
 * corresponding requests.
 *
 * Based on https://github.com/typesafehub/netty-http-pipelining - which uses netty 3
 */
public class HttpPipeliningHandler extends ChannelDuplexHandler {

    public static final int INITIAL_EVENTS_HELD = 3;

    private final int maxEventsHeld;
    private final Queue<HttpPipelinedResponse> holdingQueue;

    private int sequence = 0;
    private int nextRequiredSequence = 0;


    /**
     * @param maxEventsHeld the maximum number of channel events that will be retained prior to aborting the channel
     *                      connection. This is required as events cannot queue up indefinitely; we would run out of
     *                      memory if this was the case.
     */
    public HttpPipeliningHandler(final int maxEventsHeld) {
        this.maxEventsHeld = maxEventsHeld;
        this.holdingQueue = new PriorityQueue<>(INITIAL_EVENTS_HELD);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastHttpContent) {
            super.channelRead(ctx, new HttpPipelinedRequest((LastHttpContent) msg, sequence++));
        }
    }


    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpPipelinedResponse) {
            boolean channelShouldClose = false;

            synchronized (holdingQueue) {
                if (holdingQueue.size() < maxEventsHeld) {

                    final HttpPipelinedResponse currentEvent = (HttpPipelinedResponse) msg;
                    holdingQueue.add(currentEvent);

                    while (!holdingQueue.isEmpty()) {
                        final HttpPipelinedResponse queuedPipelinedResponse = holdingQueue.peek();

                        if (queuedPipelinedResponse.getSequenceId() != nextRequiredSequence) {
                            break;
                        }
                        holdingQueue.remove();
                        super.write(ctx, queuedPipelinedResponse.getResponse(), queuedPipelinedResponse.getPromise());
                        nextRequiredSequence++;
                    }
                } else {
                    channelShouldClose = true;
                }
            }

            if (channelShouldClose) {
                ctx.close();
            }
        } else {
            super.write(ctx, msg, promise);
        }
    }
}
