package io.axoniq.eventstore.client;

import io.axoniq.eventstore.grpc.ClusterInfo;
import io.axoniq.eventstore.grpc.NodeInfo;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 */
public class ChannelManager {
    private final Logger log = LoggerFactory.getLogger(ChannelManager.class);
    private final String certChainFile;

    private Map<NodeKey, ManagedChannel> clusterManagerChannels = new ConcurrentHashMap<>();

    public ChannelManager(String certChainFile) {
        this.certChainFile = certChainFile;
    }

    public ManagedChannel getChannel(NodeInfo nodeInfo) {
        NodeKey nodeKey = new NodeKey(nodeInfo);
        ManagedChannel channel = clusterManagerChannels.computeIfAbsent(nodeKey,
                key -> ManagedChannelUtil.createManagedChannel(nodeInfo.getHostName(), nodeInfo.getGrpcPort(), certChainFile));
        if(channel.isShutdown() || channel.isTerminated()) {
            log.debug("Connection to {} lost, reconnecting", nodeInfo.getGrpcPort());
            clusterManagerChannels.remove(nodeKey);
            return getChannel(nodeInfo);
        }
        log.debug("Got channel for connection to {}:{}, channel = {}", nodeInfo.getHostName(), nodeInfo.getGrpcPort(), channel);
        return channel;
    }

    public void cleanup() {
        clusterManagerChannels.values().forEach(ManagedChannel::shutdownNow);
    }

    public void shutdown(ClusterInfo nodeInfo) {
        NodeKey nodeKey = new NodeKey(nodeInfo.getMaster());
        ManagedChannel channel = clusterManagerChannels.remove(nodeKey);
        if( channel != null) {
            channel.shutdown();
        }
    }

    static class NodeKey {
        private final String hostName;
        private final int grpcPort;

        NodeKey(NodeInfo nodeInfo) {
            this.hostName = nodeInfo.getHostName();
            this.grpcPort = nodeInfo.getGrpcPort();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            NodeKey nodeKey = (NodeKey) o;

            return grpcPort == nodeKey.grpcPort && hostName.equals(nodeKey.hostName);
        }

        @Override
        public int hashCode() {
            int result = hostName.hashCode();
            result = 31 * result + grpcPort;
            return result;
        }
    }

}
