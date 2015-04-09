# Prototype for doing HTTP pipelining with netty4

This is a simple implementation of HTTP pipelining for netty 4. It is merely based on the existing [netty 3 implementation by Christopher Hunt](https://github.com/typesafehub/netty-http-pipelining), which is used in the play framework.

A sample pipeline looks like this

```java
ServerBootstrap serverBootstrap = new ServerBootstrap();
serverBootstrap.channel(NioServerSocketChannel.class);
serverBootstrap.group(new NioEventLoopGroup());
serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
  @Override
  protected void initChannel(SocketChannel ch) throws Exception {
    ch.pipeline()
      .addLast(new HttpServerCodec())
      .addLast(new HttpObjectAggregator(8192))
      .addLast(new HttpPipeliningHandler(size))
      .addLast(new YourCustomHandler());
    }
  });

serverBootstrap.validate();
serverBootstrap.bind(PORT);
```

For more information, you can check out the test called `HttpPipeliningHandlerTest` (just press `t` in github and type `test` to check it out).

# Limitations

* No support for HTTP chunks. You have to add the `HttpObjectAggregator` **before** the pipelining handler, so the you get a `FullHttpRequest`.

# License

This is licensed under the Apache license, see LICENSE.txt
