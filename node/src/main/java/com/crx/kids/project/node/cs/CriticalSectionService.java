package com.crx.kids.project.node.cs;

import com.crx.kids.project.node.Configuration;
import com.crx.kids.project.node.comm.NodeGateway;
import com.crx.kids.project.node.messages.BroadcastMessage;
import com.crx.kids.project.node.messages.SuzukiKasamiTokenMessage;
import com.crx.kids.project.node.net.Network;
import com.crx.kids.project.node.net.NetworkService;
import com.crx.kids.project.node.routing.RoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.IntStream;

@Service
public class CriticalSectionService {

    private static final Logger logger = LoggerFactory.getLogger(CriticalSectionService.class);

    // replace with map?!
    private static final Queue<Consumer<CriticalSectionToken>> criticalSectionProcedures = new ConcurrentLinkedQueue<>();

    @Autowired
    private RoutingService routingService;



    public int initiateSuzukiKasamiBroadcast(String path) {
        int suzukiKasamiId;
        try {
            CriticalSection.criticalSectionLock.writeLock().lock();
            suzukiKasamiId = CriticalSection.suzukiKasamiCounter.incrementAndGet();
            CriticalSection.suzukiKasamiCounterByNodes.put(Configuration.id, suzukiKasamiId);
        }
        finally {
            CriticalSection.criticalSectionLock.writeLock().unlock();
        }
        logger.info("Creating and broadcasting Suzuki-Kasami {} message to neighbours. Counter: {}", path, suzukiKasamiId);
        routingService.broadcastMessage(new BroadcastMessage<>(Configuration.id, suzukiKasamiId), path);
        return suzukiKasamiId;
    }

    @Async
    public void submitProcedureForCriticalExecution(Consumer<CriticalSectionToken> criticalSectionProcedure) {
        //TODO: check if node already have token an it is idle
        int suzukiKasamiBroadcastId = initiateSuzukiKasamiBroadcast(Network.BROADCAST_CRITICAL_SECTION);
//        criticalSectionProceduresMap.put(suzukiKasamiBroadcastId, criticalSectionProcedure);
        criticalSectionProcedures.add(criticalSectionProcedure);
        logger.info("Submitted critical section procedure for id {}", suzukiKasamiBroadcastId);

        if (CriticalSection.tokenIdle.compareAndSet(true, false)) {
            handleSuzukiKasamiToken(CriticalSection.token);
        }
    }

    @Async
    public void handleSuzukiKasamiToken(CriticalSectionToken token) {
        if (token == null) {
            logger.error("Received token is null!");
            return;
        }


        // TODO: calculate maps, check if token is old?


        Consumer<CriticalSectionToken> criticalProcedure = criticalSectionProcedures.poll();
        if (criticalProcedure == null) {
            logger.error("There is no critical section procedure. Setting token idle");
            CriticalSection.token = token;
            CriticalSection.tokenIdle.set(true);
            return;
        }

        try {
            criticalProcedure.accept(token);
            logger.info("CriticalSection procedure obtained and executed");

            token.getSuzukiKasamiNodeMap().put(Configuration.id, CriticalSection.suzukiKasamiCounterByNodes.get(Configuration.id));

            updateToken(token);
        }
        catch (Exception e) {
            logger.error("Unexpected error while executing critical procedure ", e);
        }
    }


    @Async
    public void handleSuzukiKasamiBroadcastMessage(BroadcastMessage<Integer> criticalSectionBroadcast) {
        try {
            CriticalSection.criticalSectionLock.writeLock().lock();

            CriticalSection.suzukiKasamiCounterByNodes.compute(criticalSectionBroadcast.getSender(), (sender, counter) -> {
                if (counter == null) {
                    logger.info("Setting first REQUEST message for Suzuki-Kasami and node {}. Current: {}, Received: {}", criticalSectionBroadcast.getSender(), counter, criticalSectionBroadcast.getId());

                    return criticalSectionBroadcast.getId();
                }
                if (counter >= criticalSectionBroadcast.getId()) {
                    logger.info("Received old REQUEST message for Suzuki-Kasami. Requesting node: {}, Current: {}, Received: {}", criticalSectionBroadcast.getSender(), counter, criticalSectionBroadcast.getId());
                    return counter;
                }
                else {
                    logger.info("Received new REQUEST message for Suzuki-Kasami. Requesting node: {}, Current: {}, Received: {}", criticalSectionBroadcast.getSender(), counter, criticalSectionBroadcast.getId());
                    return criticalSectionBroadcast.getId();
                }
            });

            if (CriticalSection.tokenIdle.compareAndSet(true, false)) {
                updateToken(CriticalSection.token);
            }
        }
        finally {
            CriticalSection.criticalSectionLock.writeLock().unlock();
        }
    }

    public void updateToken(CriticalSectionToken token) {


        IntStream.rangeClosed(1, Network.maxNodeInSystem).forEach(i -> {
            int rn = CriticalSection.suzukiKasamiCounterByNodes.getOrDefault(i, 0);
            int ln = token.getSuzukiKasamiNodeMap().getOrDefault(i, 0);

            if (rn == ln + 1) { // rn > ln
                boolean hasNode = token.getQueue().stream().anyMatch(node -> node == i);
                if (!hasNode) {
                    token.getQueue().add(i);
                }
                else {
                    logger.warn("Skipping adding node to queue due its existence. Node {}", i);
                }
            }

        });
//        for (int i = 1; i <= Network.maxNodeInSystem; i++) {
//
//        }

        logger.info("Token updated {}", token);

        if (token.getQueue().isEmpty()) {
            logger.warn("There are no waiter for cirital section in Queue. Setting token idle");
            CriticalSection.token = token;
            CriticalSection.tokenIdle.set(true);
        }
        else {
            CriticalSection.tokenIdle.set(false);
            CriticalSection.token = null;
            int nextNode = token.getQueue().poll();
            SuzukiKasamiTokenMessage suzukiKasamiTokenMessage = new SuzukiKasamiTokenMessage(Configuration.id, nextNode, token);
            routingService.dispatchMessage(suzukiKasamiTokenMessage, Network.CRITICAL_SECTION_TOKEN);
        }

    }

}
