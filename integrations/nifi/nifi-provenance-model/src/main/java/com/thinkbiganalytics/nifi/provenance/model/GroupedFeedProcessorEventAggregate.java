package com.thinkbiganalytics.nifi.provenance.model;

import com.google.common.collect.Lists;
import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedProcessorStatistics;
import com.thinkbiganalytics.nifi.provenance.model.stats.ProvenanceEventStats;
import com.thinkbiganalytics.nifi.provenance.model.util.ProvenanceEventUtil;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Determines a Stream by looking at the last {n} events and determines if the time between each of the events is within a given streaming threshold.
 *
 * If the event is detected as being a stream but the originating flow file event ( the one that started the job) was declared a Batch event then it will also process it
 *
 * Created by sr186054 on 8/25/16.
 */
public class GroupedFeedProcessorEventAggregate implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(GroupedFeedProcessorEventAggregate.class);

    /**
     * The name of the feed.  Derived from the process group {category}.{feed}
     */
    private String feedName;


    private String processorName;

    /**
     * The Processor Id
     */
    private String processorId;

    /**
     * The time for the Last Event that has been processed
     */
    private DateTime lastEventTime;

    /**
     * flag to check if the last event was a Stream
     * This is used to ensure the next check (if delayed) isnt accidently processed as a batch
     */
    private boolean isLastEventStream;


    /**
     * Collection of events in temporary state where the system is undecied if they are a stream or not.
     * The events have met the {@code allowedMillisBetweenEvents} time check to be considered a stream,
     * but have not yet met the {@code numberOfEventsThatMakeAStream} within the allotted time
     */
    private List<ProvenanceEventRecordDTO> potentialStreamEvents = new ArrayList<>();


    /**
     * Collection of events that have been marked as a stream
     */
    private List<ProvenanceEventRecordDTO> streamEvents = new ArrayList<>();

    /**
     * Map of last Stream events for a given parent flow file
     * This is used so the system can ensure that the Completion events for all Batchs and Streams are
     * sent to JMS so Ops Manager and fire the Event for other feeds to get triggered if listening.
     */
    private Map<String, ProvenanceEventRecordDTO> lastStreamEventByJob = new ConcurrentHashMap<>();

    /**
     * Collection of events that will be sent to jms
     */
    private Set<ProvenanceEventRecordDTO> jmsEvents = new HashSet<>();

    /**
     * The stats for the current processing
     */
    private AggregatedProcessorStatistics stats;


    /**
     * From the {@code StreamConfiguration} to determine a stream
     */
    private Long allowedMillisBetweenEvents;

    private Integer numberOfEventsThatMakeAStream;

    /**
     *
     */
    private AtomicInteger tempStreamingCount = new AtomicInteger(0);

    /**
     * Internal counters for metrics
     */
    private AtomicInteger eventCount = new AtomicInteger(0);
    private AtomicInteger batchCount = new AtomicInteger(0);
    private AtomicInteger streamingCount = new AtomicInteger(0);


    /**
     * Time when this group first got created
     */
    private DateTime initTime;


    /**
     * Lock is needed when adding, and then when the thread is collecting the events from the {@code jmsEvents} so it doesnt clear out any pending events
     */
    private final ReentrantLock lock = new ReentrantLock(true);

    public GroupedFeedProcessorEventAggregate(String feedName, String processorId, Long allowedMillisBetweenEvents, Integer numberOfEventsThatMakeAStream) {

        this.feedName = feedName;
        this.processorId = processorId;
        this.allowedMillisBetweenEvents = allowedMillisBetweenEvents;
        this.numberOfEventsThatMakeAStream = numberOfEventsThatMakeAStream;
        this.stats = new AggregatedProcessorStatistics(processorId, feedName);
        this.initTime = DateTime.now();
        log.info("new GroupedFeedProcessorEventAggregate for " + feedName + "," + processorId + " - " + this.initTime);
    }


    /**
     * Add the event to be processed
     * @param stats
     * @param event
     */
    public void add(ProvenanceEventStats stats, ProvenanceEventRecordDTO event) {
        if (event.getComponentName() != null && processorName == null) {
            processorName = event.getComponentName();
        }
        addEvent(event, stats);
        addEventStats(stats);


    }

    /**
     * Add an event from Nifi to be processed as either Stream or Batch
     */
    public GroupedFeedProcessorEventAggregate addEvent(ProvenanceEventRecordDTO event, ProvenanceEventStats stats) {
        groupEventAsStreamOrBatch(event, stats);
        lastEventTime = event.getEventTime();
        isLastEventStream = event.isStream();
        return this;
    }

    /**
     * Add an event who is determined to be the ending of the root flow file.  This is so the JMS queue will get notified when a stream completes
     * @param event
     * @return {@code true} if the event is to be added to the JMS queue {@code false} if the event has already been added
     */
    public boolean addRootFlowFileCompletionEvent(ProvenanceEventRecordDTO event) {
        if (!lastStreamEventByJob.containsKey(lastStreamEventMapKey(event))) {
            groupEventAsStreamOrBatch(event, true);
            return true;
        }
        return false;
    }

    /**
     *
     * @param event
     * @return a unique key from an {@code ProvenanceEventRecordDTO} that will be sent through even if it is a stream
     */
    private String lastStreamEventMapKey(ProvenanceEventRecordDTO event) {
        return event.getJobFlowFileId() + "_" + event.getEventType() + "_" + event.isEndOfJob() + "_" + event.isStartOfJob() + "_" + event.getComponentId() + "_" + event.isFailure();
    }

    /**
     * adds the incoming event to the collection of batch events
     * @param event
     */
    private void moveToBatch(ProvenanceEventRecordDTO event) {
        event.setIsBatchJob(true);
        if (event.isStartOfJob()) {
            event.getFlowFile().getRootFlowFile().setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.BATCH);
        }
        lastStreamEventByJob.put(lastStreamEventMapKey(event), event);
        streamEvents.add(event);
        streamingCount.incrementAndGet();

    }

    /**
     * Moves the entire collection of {@code }potentialStreamEvents} to the {@code jmsEvents} batch collection
     * and clears the {@code potentialStreamEvents} list
     * @return the list of events added as batch
     */
    private List<ProvenanceEventRecordDTO> movePotentialStreamToBatch() {
        List<ProvenanceEventRecordDTO> list = new ArrayList<>();
        if (!potentialStreamEvents.isEmpty()) {
            markFirstEventsAsBatch(potentialStreamEvents);
           list.addAll(Lists.newArrayList(potentialStreamEvents));
            jmsEvents.addAll(potentialStreamEvents);
            batchCount.addAndGet(potentialStreamEvents.size());
            potentialStreamEvents.clear();
        }
        return list;
    }

    /**
     * Moves the entire collection of {@code }potentialStreamEvents} to the {@code streamEvents} batch collection
     * and clears the {@code potentialStreamEvents} list
     * @return
     */
    private void movePotentialStreamToStream() {
        if (!potentialStreamEvents.isEmpty()) {
            potentialStreamEvents.stream().forEach(e -> {
                e.setStream(true);
                if (e.isStartOfJob()) {
                    e.getFlowFile().getRootFlowFile().setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.STREAM);
                    log.debug("Starting of job moved from potential to a stream for event {}", e);
                }

                lastStreamEventByJob.put(lastStreamEventMapKey(e), e);
                markStreamAsBatchForEventWithRelatedBatchJobs(e);
            });

            streamEvents.addAll(potentialStreamEvents);
            streamingCount.addAndGet(potentialStreamEvents.size());
            potentialStreamEvents.clear();
        }
    }

    /**
     * adds the single event to the {@code streamEvents} collection
     * @param event
     */
    private void moveToStream(ProvenanceEventRecordDTO event) {
        if(event.isStartOfJob()) {
            log.debug("Starting of job is a stream for event {}", event);
        }
        event.setStream(true);
        if (event.isStartOfJob()) {
            event.getFlowFile().getRootFlowFile().setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.STREAM);
        }
        lastStreamEventByJob.put(lastStreamEventMapKey(event), event);
        markStreamAsBatchForEventWithRelatedBatchJobs(event);
        streamEvents.add(event);
        streamingCount.incrementAndGet();

    }


    /**
     * This would handle a use case where a lot of files come in, and somehow 1 of those is sent off and runs as a batch
     *
     * @param event
     */
    private void markStreamAsBatchForEventWithRelatedBatchJobs(ProvenanceEventRecordDTO event) {
        if (event.getRelatedRootFlowFiles() != null) {
            if (event.getFlowFile().getRootFlowFile().getRelatedRootFlowFiles() != null) {
                event.getFlowFile().getRootFlowFile().getRelatedRootFlowFiles().forEach(ff -> {
                    if (ff.isBatch()) {

                        //if this ff is a stream, but the parent is a batch, ensure that all the parent events related to this ff are marked as a batch and added to be processed
                        if (event.getFlowFile().getRootFlowFile().isStream()) {
                            event.getFlowFile().getRootFlowFile().setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.BATCH);
                            Set<ProvenanceEventRecordDTO> eventsToAdd = new HashSet<ProvenanceEventRecordDTO>();
                            eventsToAdd.addAll(event.getFlowFile().getRootFlowFile().getCompletedEvents());
                            eventsToAdd.add(event);
                            eventsToAdd.forEach(e -> {
                                lastStreamEventByJob.put(lastStreamEventMapKey(e), e);
                            });
                            log.info("Turning a stream into a batch because Root was indicated as a Batch.  adding {} events.  This Event {} for jobFlowFile:  {} ", eventsToAdd.size(), event,
                                     event.getJobFlowFileId());
                            event.setStream(false);
                            //mark as Batch job and reassing jobId to match that of the batch job?
                            // event.setJobFlowFileId(ff.getId());
                            event.setIsBatchJob(true);
                            //event.getFlowFile().getRootFlowFile().setFirstEvent(ff.getFirstEvent());
                        }

                        // event.setJobEventId(ff.getFirstEvent().getEventId());
                    }
                });
            }
        }
    }


    /**
     * group the event as a Batch or a stream
     * @param event
     * @param stats
     */
    private void groupEventAsStreamOrBatch(ProvenanceEventRecordDTO event, ProvenanceEventStats stats) {

        groupEventAsStreamOrBatch(event, (stats.getJobsFinished() == 1L));
    }


    /**
     * Group as batch or stream
     * @param event
     * @param isRootFLowFileFinished flag to help determine if the Ending job event should be included in jms
     */
    private void groupEventAsStreamOrBatch(ProvenanceEventRecordDTO event, boolean isRootFLowFileFinished) {
        lock.lock();
        try {

            if (lastEventTime == null) {
                lastEventTime = event.getEventTime();
            }


            if (ProvenanceEventUtil.isCompletionEvent(event)) {
                eventCount.incrementAndGet();
                checkAndMarkAsEndOfJob(event, isRootFLowFileFinished);
                //if the event is not the first event, but the first event is a Stream then move this to a stream
                if (RootFlowFile.FIRST_EVENT_TYPE.STREAM.equals(event.getFlowFile().getRootFlowFile().getFirstEventType())) {
                    moveToStream(event);

                } else {
                    //if the current event time is before the next allotted time for a batch event, then this is a potential stream, or if the lastEvent was indicated as a stream
                    if (event.getEventTime().isBefore(lastEventTime.plus(allowedMillisBetweenEvents)) || isLastEventStream) {
                        if (tempStreamingCount.incrementAndGet() >= numberOfEventsThatMakeAStream) {
                            movePotentialStreamToStream();
                            moveToStream(event);
                        } else {
                            potentialStreamEvents.add(event);
                        }
                    } else {
                        potentialStreamEvents.add(event);
                        /// no longer a stream event
                        if (event.isStartOfJob()) {
                            log.debug("Starting of job is a converted from stream to a batch for event {} ", event);
                        }
                        List<ProvenanceEventRecordDTO> movedEvents = movePotentialStreamToBatch();

                        tempStreamingCount.set(0);
                    }
                }
            } else {
                //    log.info("Non completion event {} ", event);
            }
        } finally {
            lock.unlock();
        }
    }


    /**
     * Sets the flag on the event if this event is really the ending of the RootFlowFile
     * @param event
     * @param jobFinished
     */
    private void checkAndMarkAsEndOfJob(ProvenanceEventRecordDTO event, boolean jobFinished) {
        if (jobFinished && !event.getFlowFile().isRootFlowFile()) {
            event.setIsEndOfJob(true);
        }
    }

    /**
     * If the event is determined to be a Stream, but it started off as a batch take the subset of important events
     * using the {@code lastStreamEventByJob} map and send those through
     * @return
     */
    private List<ProvenanceEventRecordDTO> addStreamingEventsWhoseFirstEventWasABatchToQueue() {
        // each job does not need all events.  they just need the start and ending events in this batch relative to the jobflowfileid
        List<ProvenanceEventRecordDTO> events = lastStreamEventByJob.values().stream().filter(
            e -> RootFlowFile.FIRST_EVENT_TYPE.BATCH.equals(e.getFlowFile().getRootFlowFile().getFirstEventType())).collect(Collectors.toList());
        if (events != null && !events.isEmpty()) {
            jmsEvents.addAll(events);
        }
        return events;
    }

    /**
     * If the Event is a batch and is the start of the job then set the flag on the Root flow file to indicate the file is of type Batch
     * @param events
     */
    private void markFirstEventsAsBatch(List<ProvenanceEventRecordDTO> events) {
        events.stream().filter(e -> e.isStartOfJob()).map(e -> e.getFlowFile().getRootFlowFile()).forEach(ff -> ff.setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.BATCH));
    }

    /**
     * Called in a separate Timer Thread that will finish and return the necessary events in the {@code jmsEvents} batch queue to be processed by JMS
     * @return
     */
    public List<ProvenanceEventRecordDTO> collectEventsToBeSentToJmsQueue() {
        lock.lock();

        List<ProvenanceEventRecordDTO> events = null;
        try {
            DateTime now = DateTime.now();

            //Move anything that is potential to batch if the time between is greater
            if (now.isAfter(lastEventTime.plus(allowedMillisBetweenEvents))) {
                    //BATCH!
                    tempStreamingCount.set(0);
                    List<ProvenanceEventRecordDTO> movedEvents = movePotentialStreamToBatch();

            }
            //if the First Event was a Batch event we should pass this event through so it gets reconciled in the Ops Manager
          List<ProvenanceEventRecordDTO> eventsAddedToBatch = addStreamingEventsWhoseFirstEventWasABatchToQueue();

            //mark as batch
            jmsEvents.stream().forEach(e -> {
                e.setIsBatchJob(true);
            });

            //copy and clear
            events = new ArrayList<>(jmsEvents);
            jmsEvents.clear();
            streamEvents.clear();
            lastStreamEventByJob.clear();
        } finally {
            lock.unlock();
        }

        return events == null ? new ArrayList<>() : events;

    }




    private GroupedFeedProcessorEventAggregate addEventStats(ProvenanceEventStats stats) {
        if (stats != null) {
            this.stats.add(stats);
        }
        return this;
    }

    public String getFeedName() {
        return feedName;
    }

    public void setFeedName(String feedName) {
        this.feedName = feedName;
    }

    public String getProcessorId() {
        return processorId;
    }

    public void setProcessorId(String processorId) {
        this.processorId = processorId;
    }

    public String getProcessorName() {
        return processorName;
    }

    public void setProcessorName(String processorName) {
        this.processorName = processorName;
    }


    public AggregatedProcessorStatistics getStats() {
        return stats;
    }

    public void setStats(AggregatedProcessorStatistics stats) {
        this.stats = stats;
    }


    public String getSummary() {
        return stats.toString();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("FeedProcessorEventAggregate{");
        sb.append("feedName='").append(feedName).append('\'');
        sb.append(", processorId='").append(processorId).append('\'');
        sb.append(", summary='").append(getSummary()).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
