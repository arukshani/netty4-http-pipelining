package de.spinscale.netty.http.pipelining;

import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;

public class HttpPipelinedRequest {

    private final FullHttpRequest request;
    private final int sequenceId;

    public HttpPipelinedRequest(FullHttpRequest request, int sequenceId) {
        this.request = request;
        this.sequenceId = sequenceId;
    }

    public FullHttpRequest getRequest() {
        return request;
    }

    public HttpPipelinedResponse createHttpResponse(DefaultFullHttpResponse response, ChannelPromise promise) {
        return new HttpPipelinedResponse(response, promise, sequenceId);
    }
}
