package com.thinkbiganalytics.metadata.jobrepo.nifi.provenance;

/*-
 * #%L
 * thinkbig-operational-metadata-integration-service
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.EvictingQueue;
import com.thinkbiganalytics.cluster.ClusterMessage;
import com.thinkbiganalytics.cluster.ClusterService;
import com.thinkbiganalytics.cluster.ClusterServiceMessageReceiver;
import com.thinkbiganalytics.jms.JmsConstants;
import com.thinkbiganalytics.jms.QueuesMod;
import com.thinkbiganalytics.metadata.api.MetadataAccess;
import com.thinkbiganalytics.metadata.api.feed.OpsManagerFeed;
import com.thinkbiganalytics.metadata.api.jobrepo.job.BatchJobExecutionProvider;
import com.thinkbiganalytics.metadata.api.jobrepo.nifi.NifiFeedProcessorErrors;
import com.thinkbiganalytics.metadata.api.jobrepo.nifi.NifiFeedProcessorStatisticsProvider;
import com.thinkbiganalytics.metadata.api.jobrepo.nifi.NifiFeedProcessorStats;
import com.thinkbiganalytics.metadata.api.jobrepo.nifi.NifiFeedStatisticsProvider;
import com.thinkbiganalytics.metadata.api.jobrepo.nifi.NifiFeedStats;
import com.thinkbiganalytics.metadata.jpa.jobrepo.nifi.JpaNifiFeedProcessorStats;
import com.thinkbiganalytics.metadata.jpa.jobrepo.nifi.JpaNifiFeedStats;
import com.thinkbiganalytics.nifi.provenance.model.ProvenanceEventRecordDTO;
import com.thinkbiganalytics.nifi.provenance.model.ProvenanceEventRecordDTOHolder;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatistics;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatisticsHolder;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatisticsHolderV2;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatisticsHolderV3;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatisticsV2;
import com.thinkbiganalytics.nifi.provenance.model.stats.GroupedStats;
import com.thinkbiganalytics.nifi.provenance.model.stats.GroupedStatsV2;
import com.thinkbiganalytics.nifi.rest.support.NifiTemplateNameUtil;
import com.thinkbiganalytics.scheduler.JobIdentifier;
import com.thinkbiganalytics.scheduler.JobScheduler;
import com.thinkbiganalytics.scheduler.QuartzScheduler;
import com.thinkbiganalytics.scheduler.TriggerIdentifier;
import com.thinkbiganalytics.scheduler.model.DefaultJobIdentifier;
import com.thinkbiganalytics.scheduler.model.DefaultTriggerIdentifier;

import org.apache.commons.lang.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.web.api.dto.BulletinDTO;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import org.springframework.util.ResourceUtils;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedFeedProcessorStatisticsHolder;
import org.apache.activemq.ActiveMQConnectionFactory;
import java.io.Serializable;
import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Session;
import com.thinkbiganalytics.nifi.provenance.model.util.GroupedStatsUtil;
import org.json.*;
import javax.json.*;
import java.net.*;
import java.io.*;
import java.util.Arrays;

/**
 */
public class NifiStatsJmsReceiver implements ClusterServiceMessageReceiver {

    private static final Logger log = LoggerFactory.getLogger(NifiStatsJmsReceiver.class);

    @Inject
    private NifiFeedProcessorStatisticsProvider nifiEventStatisticsProvider;

    @Inject
    private MetadataAccess metadataAccess;

    @Inject
    private ProvenanceEventFeedUtil provenanceEventFeedUtil;

    @Inject
    private NifiFeedStatisticsProvider nifiFeedStatisticsProvider;

    @Inject
    private NifiBulletinExceptionExtractor nifiBulletinExceptionExtractor;

    @Inject
    private BatchJobExecutionProvider batchJobExecutionProvider;

    @Inject
    private JobScheduler jobScheduler;

    @Value("${kylo.ops.mgr.stats.compact.cron:0 0 0 1/1 * ? *}")
    private String compactStatsCronSchedule;

    @Value("${kylo.ops.mgr.stats.compact.enabled:true}")
    private boolean compactStatsEnabled;

    public static final String NIFI_FEED_PROCESSOR_ERROR_CLUSTER_TYPE = "NIFI_FEED_PROCESSOR_ERROR";

    @Inject
    private ClusterService clusterService;

    @Inject
    private ProvenanceEventReceiver provenanceEventReceiver;

    @Value("${kylo.ops.mgr.stats.nifi.bulletins.mem.size:30}")
    private Integer errorsToStorePerFeed = 30;

    @Value("${kylo.ops.mgr.stats.nifi.bulletins.persist:false}")
    private boolean persistErrors = false;

    private LoadingCache<String, Queue<NifiFeedProcessorErrors>> feedProcessorErrors = CacheBuilder.newBuilder().build(new CacheLoader<String, Queue<NifiFeedProcessorErrors>>() {
        @Override
        public Queue<NifiFeedProcessorErrors> load(String feedName) throws Exception {
            return EvictingQueue.create(errorsToStorePerFeed);
        }

    });

    private Long lastBulletinId = -1L;

    @Inject
    private RetryProvenanceEventWithDelay retryProvenanceEventWithDelay;


    private static final String JMS_LISTENER_ID = "nifiStatesJmsListener";

    private static final String JMS_LISTENER_ID2 = "nifiStatesJmsListener2";
    @PostConstruct
    private void init() {
        retryProvenanceEventWithDelay.setStatsJmsReceiver(this);
        scheduleStatsCompaction();
    }

    /**
     * Map of the summary stats.
     * This is used to see if we need to update the feed stats or not
     * to reduce the query on saving all the stats
     */
    private Map<String, JpaNifiFeedStats> latestStatsCache = new ConcurrentHashMap<>();


    private Connection connection;
    private Session session;
    private MessageProducer producer;
    /**
     * Schedule the compaction job in Quartz if the properties have this enabled with a Cron Expression
     */
    private void scheduleStatsCompaction() {
        if (compactStatsEnabled && StringUtils.isNotBlank(compactStatsCronSchedule)) {
            QuartzScheduler scheduler = (QuartzScheduler) jobScheduler;
            JobIdentifier jobIdentifier = new DefaultJobIdentifier("Compact NiFi Processor Stats", "KYLO");
            TriggerIdentifier triggerIdentifier = new DefaultTriggerIdentifier(jobIdentifier.getName(), jobIdentifier.getGroup());
            try {
                scheduler.scheduleJob(jobIdentifier, triggerIdentifier, NiFiStatsCompactionQuartzJobBean.class, compactStatsCronSchedule, null);
            } catch (ObjectAlreadyExistsException e) {
                log.info("Unable to schedule the job to compact the NiFi processor stats.  It already exists.  Most likely another Kylo node has already schceduled this job. ");
            }
            catch (SchedulerException e) {
                throw new RuntimeException("Error scheduling job: Compact NiFi Processor Stats", e);
            }
        }
    }

    /**
     * get Errors in memory for a feed
     *
     * @param feedName       the feed name
     * @param afterTimestamp and optional timestamp to look after
     */
    public List<NifiFeedProcessorErrors> getErrorsForFeed(String feedName, Long afterTimestamp) {
        return getErrorsForFeed(feedName, afterTimestamp, null);
    }

    public List<NifiFeedProcessorErrors> getErrorsForFeed(String feedName, Long startTime, Long endTime) {
        List<NifiFeedProcessorErrors> errors = null;
        Queue<NifiFeedProcessorErrors> queue = feedProcessorErrors.getUnchecked(feedName);
        if (queue != null) {
            if (startTime != null && endTime != null) {

                if (queue != null) {
                    errors =
                        queue.stream().filter(error -> error.getErrorMessageTimestamp().getMillis() >= startTime && error.getErrorMessageTimestamp().getMillis() <= endTime)
                            .collect(Collectors.toList());
                }
            } else if (startTime == null && endTime != null) {
                errors = queue.stream().filter(error -> error.getErrorMessageTimestamp().getMillis() <= endTime).collect(Collectors.toList());
            } else if (startTime != null && endTime == null) {
                errors = queue.stream().filter(error -> error.getErrorMessageTimestamp().getMillis() >= startTime).collect(Collectors.toList());
            } else {
                errors = new ArrayList<>();
                errors.addAll(queue);
            }
        } else {
            errors = Collections.emptyList();
        }

        return errors;
    }

    private String getFeedName(AggregatedFeedProcessorStatistics feedProcessorStatistics) {
        String feedName = null;
        String feedProcessorId = feedProcessorStatistics.getStartingProcessorId();
        if (feedProcessorStatistics instanceof AggregatedFeedProcessorStatisticsV2) {
            feedName = ((AggregatedFeedProcessorStatisticsV2) feedProcessorStatistics).getFeedName();
        }
        if (feedProcessorId != null && feedName == null) {
            feedName = provenanceEventFeedUtil.getFeedName(feedProcessorId);
        }
        if(StringUtils.isNotBlank(feedName)){
            feedName = NifiTemplateNameUtil.parseVersionedProcessGroupName(feedName);
        }
        return feedName;
    }
    /**
     * Ensure the cache and NiFi are up, or if not ensure the data exists in the NiFi cache to be processed
     *
     * @param stats the stats to process
     */
    public boolean readyToProcess(AggregatedFeedProcessorStatisticsHolder stats) {
        return provenanceEventFeedUtil.isNifiFlowCacheAvailable() || (!provenanceEventFeedUtil.isNifiFlowCacheAvailable() && stats.getFeedStatistics().values().stream()
            .allMatch(feedProcessorStats -> StringUtils.isNotBlank(getFeedName(feedProcessorStats))));
    }

    /**
     * If the incoming object is not a Retry object or if the incoming object is of type of Retry and we have not maxed out the retry attempts
     *
     * @param stats event holder
     * @return true  If the incoming object is not a Retry object or if the incoming object is of type of Retry and we have not maxed out the retry attempts, otherwise false
     */
    private boolean ensureValidRetryAttempt(AggregatedFeedProcessorStatisticsHolder stats) {
        return !(stats instanceof RetryAggregatedFeedProcessorStatisticsHolder) || (stats instanceof RetryAggregatedFeedProcessorStatisticsHolder
                                                                                    && ((RetryAggregatedFeedProcessorStatisticsHolder) stats).shouldRetry());
    }
    @JmsListener(id = JMS_LISTENER_ID2, destination = QueuesMod.PROVENANCE_EVENT_STATS_QUEUE2, containerFactory = JmsConstants.QUEUE_LISTENER_CONTAINER_FACTORY)
    public void receiveTopic(byte[] stats) {
        Object o = null;
        try {
            o = SerializationUtils.deserialize(stats);
        } catch (Exception e) {
            log.error("Unable to deserialize object ", e);
        }
        if (o != null && o instanceof AggregatedFeedProcessorStatisticsHolder) {
            receiveTopic((AggregatedFeedProcessorStatisticsHolder) o);
        }
    }

    @JmsListener(id = JMS_LISTENER_ID, destination = QueuesMod.PROVENANCE_EVENT_STATS_QUEUE, containerFactory = JmsConstants.QUEUE_LISTENER_CONTAINER_FACTORY)
    public void receiveTopic(AggregatedFeedProcessorStatisticsHolder stats) {

        // multiplexStats(stats);
        
        stats.getFeedStatistics().values().stream().forEach(feedProcessorStats -> {
            log.info("TENANTS Print Stats " + feedProcessorStats.toString());
        });
        

        if (readyToProcess(stats)) {
            multiplexStats(stats);
            if (false /*ensureValidRetryAttempt(stats)*/) {
                final List<AggregatedFeedProcessorStatistics> unregisteredEvents = new ArrayList<>();
                metadataAccess.commit(() -> {
                    List<NifiFeedProcessorStats> summaryStats = createSummaryStats(stats, unregisteredEvents);

                    List<JpaNifiFeedProcessorStats> failedStatsWithFlowFiles = new ArrayList<>();
                    for (NifiFeedProcessorStats stat : summaryStats) {
                        NifiFeedProcessorStats savedStats = nifiEventStatisticsProvider.create(stat);
                        if (savedStats.getFailedCount() > 0L && savedStats.getLatestFlowFileId() != null) {
                            //offload the query to nifi and merge back in
                            failedStatsWithFlowFiles.add((JpaNifiFeedProcessorStats) savedStats);
                        }
                        ensureStreamingJobExecutionRecord(stat);
                    }
                    if (stats instanceof AggregatedFeedProcessorStatisticsHolderV2) {
                        saveFeedStats((AggregatedFeedProcessorStatisticsHolderV2) stats, summaryStats);
                    }
                    if (!failedStatsWithFlowFiles.isEmpty()) {
                        assignNiFiBulletinErrors(failedStatsWithFlowFiles);
                    }
                    return summaryStats;
                }, MetadataAccess.SERVICE);

                if (clusterService.isClustered() && !unregisteredEvents.isEmpty()) {
                    //reprocess with delay
                    if (retryProvenanceEventWithDelay != null) {
                        retryProvenanceEventWithDelay.delay(stats, unregisteredEvents);
                    }
                }
            } else {
                //stop processing the events
                log.info("Unable find the feed in Ops Manager.  Not processing {} stats ", stats.getFeedStatistics().values().size());

            }
        } else {
            log.info("NiFi is not up yet.  Sending back to JMS for later dequeue ");
            throw new JmsProcessingException("Unable to process Statistics Events.  NiFi is either not up, or there is an error trying to populate the Kylo NiFi Flow Cache. ");
        }

    }

    private void multiplexStats(AggregatedFeedProcessorStatisticsHolder e) {
    //private void multiplexStats(AggregatedFeedProcessorStatistics e) {
        log.info("DEV  - LOGGING STATS JMS    ");
        log.info(e.toString());  

        boolean proceed = true;
        
        List<String> processorA = new ArrayList<>();
        List<String> processorB = new ArrayList<>();

        BufferedReader br = null;
        FileReader fr = null;
        BufferedReader br1 = null;
        FileReader fr1 = null;
        try {

            fr = new FileReader("/opt/json/usera.txt");
            br = new BufferedReader(fr);
            String sCurrentLine;
            while ((sCurrentLine = br.readLine()) != null) {
                // System.out.println(sCurrentLine);
                processorA.add(sCurrentLine);
            }
            } catch (IOException e2) {

                e2.printStackTrace();
                log.info(e2.toString());
                proceed = false;
            } finally {

            try {

                if (br != null)
                    br.close();

                if (fr != null)
                    fr.close();

            } catch (IOException ex2) {

                ex2.printStackTrace();
                log.info(ex2.toString());
                proceed = false;
            }

        }

        try {

            fr1 = new FileReader("/opt/json/userb.txt");
            br1 = new BufferedReader(fr1);
            String sCurrentLine1;
            while ((sCurrentLine1 = br1.readLine()) != null) {
                // System.out.println(sCurrentLine);
                processorB.add(sCurrentLine1);
            }
        } catch (IOException e1) {

            e1.printStackTrace();
            log.info(e1.toString());
            proceed = false;

        } finally {

            try {

                if (br1 != null)
                    br1.close();

                if (fr1 != null)
                    fr1.close();

            } catch (IOException ex1) {

                ex1.printStackTrace();
                log.info(ex1.toString());
                proceed = false;
            }

        }

            String KYLO_PROVENANCE_EVENT_STATS_QUEUE = "thinkbig.provenance-event-stats-a";
            /*
            if (processorA.contains(e.getStartingProcessorId())) {

                log.info("These stats are from user A");
                KYLO_PROVENANCE_EVENT_STATS_QUEUE = "thinkbig.provenance-event-stats-a";
            } else if (processorB.contains(e.getStartingProcessorId())) {
                log.info("These stats are from user B");
                KYLO_PROVENANCE_EVENT_STATS_QUEUE = "thinkbig.provenance-event-stats-b";
            } else {
                proceed = false;
                log.info("THIS STAT IS NOT FROM OUR TENANTS" + e.toString());
            }
            */

            if (proceed) {
                /*
                // ProvenanceEventRecordDTOHolder eventRecordDTOHolder = new ProvenanceEventRecordDTOHolder();
                AggregatedFeedProcessorStatisticsHolder statDTOHolder = new AggregatedFeedProcessorStatisticsHolder();
                // List<AggregatedFeedProcessorStatistics> batchEvents = new ArrayList<>();
                List<AggregatedFeedProcessorStatistics> nifiFeedProcessorStatsList = new ArrayList<>();
                nifiFeedProcessorStatsList.add(e);
                // eventRecordDTOHolder.setEvents(batchEvents);
                statDTOHolder.setFeedStatistics(nifiFeedProcessorStatsList);
                // WRITE NEW QUEUE
                */
                String jmsUrl = "tcp://localhost:61616";

                log.info("Sending {} STATS to NEW JMS ", e);
                // AggregatedFeedProcessorStatisticsHolder stats = GroupedStatsUtil.gatherStats(batchEvents);
                try {
                    Connection connection2 = getOrCreateJmsConnection(jmsUrl);
                    Session session2 = getOrCreateSession(connection2);
                    MessageProducer producerStats = getOrCreateProducer(session2, KYLO_PROVENANCE_EVENT_STATS_QUEUE);
                    Message mp = session2.createObjectMessage(e);
                    producerStats.send(mp);
                } catch (Exception ex) {
                    log.info("ERROR sending STATS to NEW JMS");
                    log.info(ex.toString());
                    ex.printStackTrace();
                }
                
                log.info("Events and Stats successfully sent to NEW JMS");
        }
    }

    private Connection getOrCreateJmsConnection(String url) throws Exception {
        if(connection == null) {
            // Create a ConnectionFactory
            ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(url);

            // Create a Connection
            Connection connection = connectionFactory.createConnection();
            connection.start();
            this.connection = connection;
        }
        return connection;
    }

    private Session getOrCreateSession(Connection connection) throws Exception {
        if(session == null) {
            // Create a Session
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            this.session = session;
        }
        return session;
    }


    private MessageProducer getOrCreateProducer(Session session, String queueName) throws Exception{

        if(producer == null) {
            // Create the destination (Topic or Queue)
            Destination destination = session.createQueue(queueName);
            MessageProducer producer = session.createProducer(destination);
            producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
            this.producer = producer;
        }
        return producer;
    }


    private void assignNiFiBulletinErrors(List<JpaNifiFeedProcessorStats> stats) {

        //might need to query with the 'after' parameter

        //group the FeedStats by processorId_flowfileId

        Map<String, Map<String, List<JpaNifiFeedProcessorStats>>>
            processorFlowFilesStats =
            stats.stream().filter(s -> s.getProcessorId() != null)
                .collect(Collectors.groupingBy(NifiFeedProcessorStats::getProcessorId, Collectors.groupingBy(NifiFeedProcessorStats::getLatestFlowFileId)));

        Set<String> processorIds = processorFlowFilesStats.keySet();
        //strip out those processorIds that are part of a reusable flow
        Set<String> nonReusableFlowProcessorIds = processorIds.stream().filter(processorId -> !provenanceEventFeedUtil.isReusableFlowProcessor(processorId)).collect(Collectors.toSet());

        //find all errors for the processors
        List<BulletinDTO> errors = nifiBulletinExceptionExtractor.getErrorBulletinsForProcessorId(processorIds, lastBulletinId);

        if (errors != null && !errors.isEmpty()) {
            Set<JpaNifiFeedProcessorStats> statsToUpdate = new HashSet<>();
            // first look for matching feed flow and processor ids.  otherwise look for processor id matches that are not part of reusable flows
            errors.stream().forEach(b -> {
                stats.stream().forEach(stat -> {
                    if (stat.getLatestFlowFileId() != null && b.getSourceId().equalsIgnoreCase(stat.getProcessorId()) && b.getMessage().contains(stat.getLatestFlowFileId())) {
                        stat.setErrorMessageTimestamp(getAdjustBulletinDateTime(b));
                        stat.setErrorMessages(b.getMessage());
                        addFeedProcessorError(stat);
                        statsToUpdate.add(stat);
                    } else if (nonReusableFlowProcessorIds.contains(b.getSourceId()) && b.getSourceId().equalsIgnoreCase(stat.getProcessorId())) {
                        stat.setErrorMessageTimestamp(getAdjustBulletinDateTime(b));
                        stat.setErrorMessages(b.getMessage());
                        addFeedProcessorError(stat);
                        statsToUpdate.add(stat);
                    }
                });
            });
            lastBulletinId = errors.stream().mapToLong(b -> b.getId()).max().getAsLong();

            if (!statsToUpdate.isEmpty()) {
                notifyClusterOfFeedProcessorErrors(statsToUpdate);
                if (persistErrors) {
                    nifiEventStatisticsProvider.save(new ArrayList<>(statsToUpdate));
                }
            }
        }
    }

    /**
     * the BulletinDTO comes back from nifi as a Date object in the year 1970
     * We need to convert this to the current date and account for DST
     *
     * @param b the bulletin
     */
    private DateTime getAdjustBulletinDateTime(BulletinDTO b) {
        DateTimeZone defaultZone = DateTimeZone.getDefault();

        int currentOffsetMillis = defaultZone.getOffset(DateTime.now().getMillis());
        double currentOffsetHours = (double) currentOffsetMillis / 1000d / 60d / 60d;

        long bulletinOffsetMillis = DateTimeZone.getDefault().getOffset(b.getTimestamp().getTime());

        double bulletinOffsetHours = (double) bulletinOffsetMillis / 1000d / 60d / 60d;

        DateTime adjustedTime = new DateTime(b.getTimestamp()).withDayOfYear(DateTime.now().getDayOfYear()).withYear(DateTime.now().getYear());
        int adjustedHours = 0;
        if (currentOffsetHours != bulletinOffsetHours) {
            adjustedHours = new Double(bulletinOffsetHours - currentOffsetHours).intValue();
            adjustedTime = adjustedTime.plusHours(-adjustedHours);
        }
        return adjustedTime;
    }

    private void addFeedProcessorError(NifiFeedProcessorErrors error) {
        Queue<NifiFeedProcessorErrors> q = feedProcessorErrors.getUnchecked(error.getFeedName());
        if (q != null) {
            q.add(error);
        }
    }


    /**
     * Save the running totals for the feed
     */
    private Map<String, JpaNifiFeedStats> saveFeedStats(AggregatedFeedProcessorStatisticsHolderV2 holder, List<NifiFeedProcessorStats> summaryStats) {
        Map<String, JpaNifiFeedStats> feedStatsMap = new HashMap<>();

        if (summaryStats != null) {
            Map<String, Long> feedLatestTimestamp = summaryStats.stream().collect(Collectors.toMap(NifiFeedProcessorStats::getFeedName, stats -> stats.getMinEventTime().getMillis(), Long::max));
            feedLatestTimestamp.entrySet().stream().forEach(e -> {
                String feedName = e.getKey();
                Long timestamp = e.getValue();
                JpaNifiFeedStats stats = feedStatsMap.computeIfAbsent(feedName, name -> new JpaNifiFeedStats(feedName));
                OpsManagerFeed opsManagerFeed = provenanceEventFeedUtil.getFeed(feedName);
                if (opsManagerFeed != null) {
                    stats.setFeedId(new JpaNifiFeedStats.OpsManagerFeedId(opsManagerFeed.getId().toString()));
                }
                stats.setLastActivityTimestamp(timestamp);
            });
        }
        if (holder.getProcessorIdRunningFlows() != null) {
            holder.getProcessorIdRunningFlows().entrySet().stream().forEach(e -> {
                String feedProcessorId = e.getKey();
                Long runningCount = e.getValue();
                String feedName = provenanceEventFeedUtil.getFeedName(feedProcessorId);  //ensure not null
                if (StringUtils.isNotBlank(feedName)) {
                    JpaNifiFeedStats stats = feedStatsMap.computeIfAbsent(feedName, name -> new JpaNifiFeedStats(feedName));
                    OpsManagerFeed opsManagerFeed = provenanceEventFeedUtil.getFeed(feedName);
                    if (opsManagerFeed != null) {
                        stats.setFeedId(new JpaNifiFeedStats.OpsManagerFeedId(opsManagerFeed.getId().toString()));
                        stats.setStream(opsManagerFeed.isStream());
                    }
                    stats.addRunningFeedFlows(runningCount);
                    if (holder instanceof AggregatedFeedProcessorStatisticsHolderV3) {
                        stats.setTime(((AggregatedFeedProcessorStatisticsHolderV3) holder).getTimestamp());
                        if (stats.getLastActivityTimestamp() == null) {
                            stats.setLastActivityTimestamp(((AggregatedFeedProcessorStatisticsHolderV3) holder).getTimestamp());
                        }
                    } else {
                        stats.setTime(DateTime.now().getMillis());
                    }
                    if (stats.getLastActivityTimestamp() == null) {
                        log.warn("The JpaNifiFeedStats.lastActivityTimestamp for the feed {} is NULL.  The JMS Class was: {}", feedName, holder.getClass().getSimpleName());
                    }
                }
            });
        }

        //group stats to save together by feed name
        if (!feedStatsMap.isEmpty()) {
            //only save those that have changed
            List<NifiFeedStats> updatedStats = feedStatsMap.entrySet().stream().map(e -> e.getValue()).collect(Collectors.toList());

            //if the running flows are 0 and its streaming we should try back to see if this feed is running or not
            updatedStats.stream().filter(s -> s.isStream()).forEach(stats -> {
                latestStatsCache.put(stats.getFeedName(), (JpaNifiFeedStats) stats);
                if (stats.getRunningFeedFlows() == 0L) {
                    batchJobExecutionProvider.markStreamingFeedAsStopped(stats.getFeedName());
                } else {
                    batchJobExecutionProvider.markStreamingFeedAsStarted(stats.getFeedName());
                }
            });
            nifiFeedStatisticsProvider.saveLatestFeedStats(updatedStats);
        }
        return feedStatsMap;
    }

    private List<NifiFeedProcessorStats> createSummaryStats(AggregatedFeedProcessorStatisticsHolder holder, final List<AggregatedFeedProcessorStatistics> unregisteredEvents) {
        List<NifiFeedProcessorStats> nifiFeedProcessorStatsList = new ArrayList<>();

        holder.getFeedStatistics().values().stream().forEach(feedProcessorStats -> {
            Long collectionIntervalMillis = feedProcessorStats.getCollectionIntervalMillis();
            String feedProcessorId = feedProcessorStats.getStartingProcessorId();

            String feedName = getFeedName(feedProcessorStats);
            if (StringUtils.isNotBlank(feedName)) {
                String feedProcessGroupId = provenanceEventFeedUtil.getFeedProcessGroupId(feedProcessorId);

                feedProcessorStats.getProcessorStats().values().forEach(processorStats -> {
                    processorStats.getStats().values().stream().forEach(stats -> {
                        NifiFeedProcessorStats
                            nifiFeedProcessorStats =
                            toSummaryStats(stats);
                        nifiFeedProcessorStats.setFeedName(feedName);
                        nifiFeedProcessorStats
                            .setProcessorId(processorStats.getProcessorId());
                        nifiFeedProcessorStats.setCollectionIntervalSeconds(
                            (collectionIntervalMillis / 1000));
                        if (holder instanceof AggregatedFeedProcessorStatisticsHolderV2) {
                            nifiFeedProcessorStats
                                .setCollectionId(((AggregatedFeedProcessorStatisticsHolderV2) holder).getCollectionId());
                        }
                        String
                            processorName =
                            provenanceEventFeedUtil
                                .getProcessorName(processorStats.getProcessorId());
                        if (processorName == null) {
                            processorName = processorStats.getProcessorName();
                        }
                        nifiFeedProcessorStats.setProcessorName(processorName);
                        nifiFeedProcessorStats
                            .setFeedProcessGroupId(feedProcessGroupId);
                        nifiFeedProcessorStatsList.add(nifiFeedProcessorStats);
                    });
                });
            } else {
                unregisteredEvents.add(feedProcessorStats);
            }


        });


        return nifiFeedProcessorStatsList;

    }

    private void ensureStreamingJobExecutionRecord(NifiFeedProcessorStats stats) {
        if(stats.getJobsStarted() >0 || stats.getJobsFinished() >0) {
            OpsManagerFeed feed = provenanceEventFeedUtil.getFeed(stats.getFeedName());
            if(feed != null && feed.isStream()) {
                ProvenanceEventRecordDTO event = new ProvenanceEventRecordDTO();
                event.setEventId(stats.getMaxEventId());
                event.setEventTime(stats.getMinEventTime().getMillis());
                event.setEventDuration(stats.getDuration());
                event.setFlowFileUuid(stats.getLatestFlowFileId());
                event.setJobFlowFileId(stats.getLatestFlowFileId());
                event.setComponentId(stats.getProcessorId());
                event.setComponentName(stats.getProcessorName());
                event.setIsFailure(stats.getFailedCount() > 0L);
                event.setStream(feed.isStream());
                event.setIsStartOfJob(stats.getJobsStarted() > 0L);
                event.setIsFinalJobEvent(stats.getJobsFinished() > 0L);
                event.setFeedProcessGroupId(stats.getFeedProcessGroupId());
                event.setFeedName(stats.getFeedName());
                ProvenanceEventRecordDTOHolder holder = new ProvenanceEventRecordDTOHolder();
                List<ProvenanceEventRecordDTO> events = new ArrayList<>();
                events.add(event);
                holder.setEvents(events);
                log.debug("Ensuring Streaming Feed Event: {} has a respective JobExecution record ",event);
                provenanceEventReceiver.receiveEvents(holder);
            }
        }


    }


    private NifiFeedProcessorStats toSummaryStats(GroupedStats groupedStats) {
        NifiFeedProcessorStats nifiFeedProcessorStats = new JpaNifiFeedProcessorStats();
        nifiFeedProcessorStats.setTotalCount(groupedStats.getTotalCount());
        nifiFeedProcessorStats.setFlowFilesFinished(groupedStats.getFlowFilesFinished());
        nifiFeedProcessorStats.setFlowFilesStarted(groupedStats.getFlowFilesStarted());
        nifiFeedProcessorStats.setCollectionId(groupedStats.getGroupKey());
        nifiFeedProcessorStats.setBytesIn(groupedStats.getBytesIn());
        nifiFeedProcessorStats.setBytesOut(groupedStats.getBytesOut());
        nifiFeedProcessorStats.setDuration(groupedStats.getDuration());
        nifiFeedProcessorStats.setJobsFinished(groupedStats.getJobsFinished());
        nifiFeedProcessorStats.setJobsStarted(groupedStats.getJobsStarted());
        nifiFeedProcessorStats.setProcessorsFailed(groupedStats.getProcessorsFailed());
        nifiFeedProcessorStats.setCollectionTime(new DateTime(groupedStats.getTime()));
        nifiFeedProcessorStats.setMinEventTime(new DateTime(groupedStats.getMinTime()));
        nifiFeedProcessorStats.setMinEventTimeMillis(nifiFeedProcessorStats.getMinEventTime().getMillis());
        nifiFeedProcessorStats.setMaxEventTime(new DateTime(groupedStats.getMaxTime()));
        nifiFeedProcessorStats.setJobsFailed(groupedStats.getJobsFailed());
        nifiFeedProcessorStats.setSuccessfulJobDuration(groupedStats.getSuccessfulJobDuration());
        nifiFeedProcessorStats.setJobDuration(groupedStats.getJobDuration());
        nifiFeedProcessorStats.setMaxEventId(groupedStats.getMaxEventId());
        nifiFeedProcessorStats.setFailedCount(groupedStats.getProcessorsFailed());
        if (groupedStats instanceof GroupedStatsV2) {
            nifiFeedProcessorStats.setLatestFlowFileId(((GroupedStatsV2) groupedStats).getLatestFlowFileId());
        }
        if (provenanceEventFeedUtil.isFailure(groupedStats.getSourceConnectionIdentifier())) {
            nifiFeedProcessorStats.setFailedCount(groupedStats.getTotalCount() + groupedStats.getProcessorsFailed());
        }

        return nifiFeedProcessorStats;
    }


    public boolean isPersistErrors() {
        return persistErrors;
    }

    @Override
    public void onMessageReceived(String from, ClusterMessage message) {

        if (message != null && NIFI_FEED_PROCESSOR_ERROR_CLUSTER_TYPE.equalsIgnoreCase(message.getType())) {
            NifiFeedProcessorStatsErrorClusterMessage content = (NifiFeedProcessorStatsErrorClusterMessage) message.getMessage();
            if (content != null && content.getErrors() != null) {
                content.getErrors().stream().forEach(error -> addFeedProcessorError(error));
            }
        }
    }

    private void notifyClusterOfFeedProcessorErrors(Set<? extends NifiFeedProcessorErrors> errors) {
        if (clusterService.isClustered()) {
            clusterService.sendMessageToOthers(NIFI_FEED_PROCESSOR_ERROR_CLUSTER_TYPE, new NifiFeedProcessorStatsErrorClusterMessage(errors));
        }
    }

}
