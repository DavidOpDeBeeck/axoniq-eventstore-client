package io.axoniq.eventstore.gateway;

import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLException;
import java.io.File;

/**
 * Author: marc
 */
public class ManagedChannelUtil {
    public static ManagedChannel createManagedChannel( String host, int port, String certChainFile) {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(host, port);
        if (certChainFile != null ) {
            try {
                SslContext sslContext = GrpcSslContexts.forClient()
                        .trustManager(new File(certChainFile))
                        .build();
                builder.sslContext(sslContext);
            } catch (SSLException e) {
                throw new RuntimeException("Couldn't set up SSL context", e);
            }
        } else {
            builder.usePlaintext(true);
        }
        return builder.build();
    }
}
