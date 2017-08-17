package io.axoniq.eventstore.axon;

import io.axoniq.eventstore.EventStoreConfiguration;
import io.axoniq.eventstore.EventWithToken;
import io.axoniq.eventstore.gateway.EventStoreGateway;
import io.axoniq.eventstore.grpc.*;
import io.axoniq.eventstore.util.DuplexStreamObserver;
import io.axoniq.eventstore.util.GrpcConnection;
import org.axonframework.common.Assert;
import org.axonframework.eventhandling.AbstractEventBus;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.eventsourcing.eventstore.*;
import org.axonframework.messaging.unitofwork.CurrentUnitOfWork;
import org.axonframework.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author Zoltan Altfatter
 */
public class AxoniqEventStore extends AbstractEventBus implements EventStore {

    private final Logger logger = LoggerFactory.getLogger(AxoniqEventStore.class);
    private final String GRPC_SENDER = this + "/GRPC_SENDER";
    private final EventStoreConfiguration eventStoreConfiguration;
    private final EventStoreGateway eventStoreGateway;
    private PayloadMapper payloadMapper;

    public AxoniqEventStore(EventStoreConfiguration eventStoreConfiguration, EventStoreGateway eventStoreGateway, Serializer serializer) {
        this.eventStoreConfiguration = eventStoreConfiguration;
        this.eventStoreGateway = eventStoreGateway;
        payloadMapper = new PayloadMapper(serializer);
    }

    @Override
    public DomainEventStream readEvents(String aggregateIdentifier) {
        logger.debug("Reading events for aggregate id {}", aggregateIdentifier);
        GetAggregateEventsRequest request = GetAggregateEventsRequest.newBuilder().setAggregateId(aggregateIdentifier).build();
        try {
            return DomainEventStream.of(eventStoreGateway.listAggregateEvents(request).map(payloadMapper::map));
        } catch (Throwable e) {
            throw convertExcpetion(e);
        }
    }

    private RuntimeException convertExcpetion(Throwable ex) {
        if( ex instanceof ExecutionException) ex = ex.getCause();
        return (ex instanceof RuntimeException) ? (RuntimeException) ex : new RuntimeException(ex);
    }


    @Override
    public TrackingEventStream openStream(TrackingToken trackingToken) {
        Assert.isTrue(trackingToken == null || trackingToken instanceof GlobalSequenceTrackingToken, () -> "Invalid tracking token type. Must be GlobalSequenceTrackingToken.");
        long nextToken = trackingToken == null ? 0 : ((GlobalSequenceTrackingToken) trackingToken).getGlobalIndex() + 1;
        EventConsumer consumer = new EventConsumer(payloadMapper);

        logger.info("open stream: {}", nextToken);
        DuplexStreamObserver<GetEventsRequest, EventWithToken> observer = new DuplexStreamObserver<>(eventStoreGateway::listEvents,
                (eventWithToken, getEventsRequestStreamObserver) -> {
                    logger.debug("Received event with token: {}", eventWithToken.getToken());
                    consumer.push(eventWithToken);
                },
                throwable -> {
                    logger.error("Failed to receive events", throwable);
                    consumer.fail( convertExcpetion(throwable));
                });
        GetEventsRequest request = GetEventsRequest.newBuilder()
                .setTrackingToken(nextToken)
                .setNumberOfPermits(eventStoreConfiguration.getInitialNrOfPermits())
                .build();

        GetEventsRequest nextRequest = GetEventsRequest.newBuilder().setNumberOfPermits(eventStoreConfiguration.getNrOfNewPermits()).build();
        observer.start(request, nextRequest, eventStoreConfiguration.getInitialNrOfPermits(), eventStoreConfiguration.getNrOfNewPermits(), eventStoreConfiguration.getNewPermitsThreshold());

        consumer.registerCloseListener((eventConsumer) -> observer.stop());
        return consumer;
    }

    protected void prepareCommit(List<? extends EventMessage<?>> events) {
        GrpcConnection sender = CurrentUnitOfWork.get().getOrComputeResource(GRPC_SENDER, k -> {
            GrpcConnection grpcConnection = eventStoreGateway.createAppendEventConnection();
            CurrentUnitOfWork.get().onRollback(u -> grpcConnection.rollback(u.getExecutionResult().getExceptionResult()));
            return grpcConnection;
        });

        for (EventMessage<?> eventMessage : events) {
            sender.send(payloadMapper.map(eventMessage));
        }

        super.prepareCommit(events);
    }

    protected void commit(List<? extends EventMessage<?>> events) {
        super.commit(events);

        GrpcConnection sender = CurrentUnitOfWork.get().getResource(GRPC_SENDER);
        try {
            sender.commit();
        } catch (Throwable e) {
            throw convertExcpetion(e);
        }
    }

    protected void afterCommit(List<? extends EventMessage<?>> events) {
        super.afterCommit(events);
    }

    @Override
    public void storeSnapshot(DomainEventMessage<?> snapshot) {
        try {
            eventStoreGateway.appendSnapshot(payloadMapper.map(snapshot)).get(10, TimeUnit.SECONDS);
        } catch (Throwable e) {
            throw convertExcpetion(e);
        }
        logger.info("Store snapshot: {}", snapshot);
    }

}
