package de.spinscale.netty.http.pipelining;

import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;

public class HttpPipelinedResponse implements Comparable<HttpPipelinedResponse> {

    private final DefaultFullHttpResponse response;
    private final ChannelPromise promise;
    private final int sequenceId;

    public HttpPipelinedResponse(DefaultFullHttpResponse response, ChannelPromise promise, int sequenceId) {
        this.response = response;
        this.promise = promise;
        this.sequenceId = sequenceId;
    }

    public int getSequenceId() {
        return sequenceId;
    }

    public DefaultFullHttpResponse getResponse() {
        return response;
    }

    public ChannelPromise getPromise() {
        return promise;
    }

    @Override
    public int compareTo(HttpPipelinedResponse other) {
        return this.sequenceId - other.getSequenceId();
    }
}
