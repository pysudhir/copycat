/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.copycat.server.storage;

import io.atomix.catalyst.buffer.Buffer;
import io.atomix.catalyst.buffer.FileBuffer;
import io.atomix.catalyst.buffer.HeapBuffer;
import io.atomix.catalyst.buffer.MappedBuffer;
import io.atomix.catalyst.serializer.Serializer;
import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.server.storage.util.OffsetIndex;
import io.atomix.copycat.server.storage.util.OffsetPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Manages creation and deletion of {@link Segment}s of the {@link Log}.
 * <p>
 * The segment manager keeps track of segments in a given {@link Log} and provides an interface to loading, retrieving,
 * and compacting those segments.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class SegmentManager implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(SegmentManager.class);
  private static final int DEFAULT_BUFFER_SIZE = 1024 * 1024;
  
  private final String name;
  private final Storage storage;
  private final Serializer serializer;
  private final NavigableMap<Long, Segment> segments = new ConcurrentSkipListMap<>();
  private Segment currentSegment;
  private long commitIndex;

  /**
   * @throws NullPointerException if {@code segments} is null
   */
  public SegmentManager(String name, Storage storage, Serializer serializer) {
    this.name = Assert.notNull(name, "name");
    this.storage = Assert.notNull(storage, "storage");
    this.serializer = Assert.notNull(serializer, "serializer");
    open();
  }

  /**
   * Returns the entry serializer.
   *
   * @return The entry serializer.
   */
  public Serializer serializer() {
    return serializer;
  }

  /**
   * Sets the log commit index.
   *
   * @param index The log commit index.
   * @return The segment manager.
   */
  SegmentManager commitIndex(long index) {
    this.commitIndex = Math.max(this.commitIndex, index);
    return this;
  }

  /**
   * Returns the log compact index.
   *
   * @return The log compact index.
   */
  public long commitIndex() {
    return commitIndex;
  }

  /**
   * Opens the segments.
   */
  private void open() {
    // Load existing log segments from disk.
    for (Segment segment : loadSegments()) {
      segments.put(segment.descriptor().index(), segment);
    }

    // If a segment doesn't already exist, create an initial segment starting at index 1.
    if (!segments.isEmpty()) {
      currentSegment = segments.lastEntry().getValue();
    } else {
      SegmentDescriptor descriptor = SegmentDescriptor.builder()
        .withId(1)
        .withVersion(1)
        .withIndex(1)
        .withMaxSegmentSize(storage.maxSegmentSize())
        .withMaxEntries(storage.maxEntriesPerSegment())
        .build();

      descriptor.lock();

      currentSegment = createSegment(descriptor);
      currentSegment.descriptor().update(System.currentTimeMillis());
      currentSegment.descriptor().lock();

      segments.put(1l, currentSegment);
    }
  }

  /**
   * Asserts that the manager is open.
   * 
   * @throws IllegalStateException if the segment manager is not open
   */
  private void assertOpen() {
    Assert.state(currentSegment != null, "segment manager not open");
  }

  /**
   * Returns the current segment.
   *
   * @return The current segment.
   */
  public Segment currentSegment() {
    return currentSegment != null ? currentSegment : lastSegment();
  }

  /**
   * Resets the current segment, creating a new segment if necessary.
   */
  private void resetCurrentSegment() {
    Segment lastSegment = lastSegment();
    if (lastSegment != null) {
      currentSegment = lastSegment;
    } else {
      SegmentDescriptor descriptor = SegmentDescriptor.builder()
        .withId(1)
        .withVersion(1)
        .withIndex(1)
        .withMaxSegmentSize(storage.maxSegmentSize())
        .withMaxEntries(storage.maxEntriesPerSegment())
        .build();
      descriptor.lock();

      currentSegment = createSegment(descriptor);

      segments.put(1L, currentSegment);
    }
  }

  /**
   * Returns the first segment in the log.
   * 
   * @throws IllegalStateException if the segment manager is not open
   */
  public Segment firstSegment() {
    assertOpen();
    Map.Entry<Long, Segment> segment = segments.firstEntry();
    return segment != null ? segment.getValue() : null;
  }

  /**
   * Returns the last segment in the log.
   * 
   * @throws IllegalStateException if the segment manager is not open
   */
  public Segment lastSegment() {
    assertOpen();
    Map.Entry<Long, Segment> segment = segments.lastEntry();
    return segment != null ? segment.getValue() : null;
  }

  /**
   * Creates and returns the next segment.
   *
   * @return The next segment.
   * @throws IllegalStateException if the segment manager is not open
   */
  public Segment nextSegment() {
    assertOpen();
    Segment lastSegment = lastSegment();
    SegmentDescriptor descriptor = SegmentDescriptor.builder()
      .withId(lastSegment != null ? lastSegment.descriptor().id() + 1 : 1)
      .withVersion(1)
      .withIndex(currentSegment.lastIndex() + 1)
      .withMaxSegmentSize(storage.maxSegmentSize())
      .withMaxEntries(storage.maxEntriesPerSegment())
      .build();
    descriptor.lock();

    currentSegment = createSegment(descriptor);

    segments.put(descriptor.index(), currentSegment);
    return currentSegment;
  }

  /**
   * Returns the collection of segments.
   *
   * @return An ordered collection of segments.
   */
  public Collection<Segment> segments() {
    return segments.values();
  }

  /**
   * Returns the collection of segments in reverse order.
   *
   * @return A reverse ordered collection of segments.
   */
  public Collection<Segment> reverseSegments() {
    return segments.descendingMap().values();
  }

  /**
   * Returns the segment for the given index.
   *
   * @param index The index for which to return the segment.
   * @throws IllegalStateException if the segment manager is not open
   */
  public Segment segment(long index) {
    assertOpen();
    // Check if the current segment contains the given index first in order to prevent an unnecessary map lookup.
    if (currentSegment != null && currentSegment.validIndex(index))
      return currentSegment;

    // If the index is in another segment, get the entry with the next lowest first index.
    Map.Entry<Long, Segment> segment = segments.floorEntry(index);
    return segment != null ? segment.getValue() : null;
  }

  /**
   * Inserts a segment.
   *
   * @param segment The segment to insert.
   * @throws IllegalStateException if the segment is unknown
   */
  public synchronized void replaceSegments(Collection<Segment> segments, Segment segment) {
    // Update the segment descriptor and lock the segment.
    segment.descriptor().update(System.currentTimeMillis());
    segment.descriptor().lock();

    // Iterate through old segments and remove them from the segments list.
    for (Segment oldSegment : segments) {
      if (!this.segments.containsKey(oldSegment.index())) {
        throw new IllegalArgumentException("unknown segment at index: " + oldSegment.index());
      }
      this.segments.remove(oldSegment.index());
    }

    // Put the new segment in the segments list.
    this.segments.put(segment.index(), segment);

    resetCurrentSegment();
  }

  /**
   * Removes a segment.
   *
   * @param segment The segment to remove.
   */
  public synchronized void removeSegment(Segment segment) {
    segments.remove(segment.index());
    segment.close();
    segment.delete();
    resetCurrentSegment();
  }

  /**
   * Creates a new segment.
   */
  public Segment createSegment(SegmentDescriptor descriptor) {
    switch (storage.level()) {
      case MEMORY:
        return createMemorySegment(descriptor);
      case MAPPED:
        return createMappedSegment(descriptor);
      case DISK:
        return createDiskSegment(descriptor);
      default:
        throw new AssertionError();
    }
  }

  /**
   * Creates a new segment.
   */
  private Segment createDiskSegment(SegmentDescriptor descriptor) {
    File segmentFile = SegmentFile.createSegmentFile(name, storage.directory(), descriptor.id(), descriptor.version());
    Buffer buffer = FileBuffer.allocate(segmentFile, Math.min(DEFAULT_BUFFER_SIZE, descriptor.maxSegmentSize()), Integer.MAX_VALUE);
    descriptor.copyTo(buffer);
    Segment segment = new Segment(buffer.slice(), descriptor, createIndex(descriptor), new OffsetPredicate(), serializer.clone(), this);
    LOGGER.debug("Created segment: {}", segment);
    return segment;
  }

  /**
   * Creates a new segment.
   */
  private Segment createMappedSegment(SegmentDescriptor descriptor) {
    File segmentFile = SegmentFile.createSegmentFile(name, storage.directory(), descriptor.id(), descriptor.version());
    Buffer buffer = MappedBuffer.allocate(segmentFile, Math.min(DEFAULT_BUFFER_SIZE, descriptor.maxSegmentSize()), Integer.MAX_VALUE);
    descriptor.copyTo(buffer);
    Segment segment = new Segment(buffer.slice(), descriptor, createIndex(descriptor), new OffsetPredicate(), serializer.clone(), this);
    LOGGER.debug("Created segment: {}", segment);
    return segment;
  }

  /**
   * Creates a new segment.
   */
  private Segment createMemorySegment(SegmentDescriptor descriptor) {
    Buffer buffer = HeapBuffer.allocate(Math.min(DEFAULT_BUFFER_SIZE, descriptor.maxSegmentSize()), Integer.MAX_VALUE);
    descriptor.copyTo(buffer);
    Segment segment = new Segment(buffer.slice(), descriptor, createIndex(descriptor), new OffsetPredicate(), serializer.clone(), this);
    LOGGER.debug("Created segment: {}", segment);
    return segment;
  }

  /**
   * Loads a segment.
   */
  public Segment loadSegment(long segmentId, long segmentVersion) {
    switch (storage.level()) {
      case MEMORY:
        return loadMemorySegment(segmentId, segmentVersion);
      case MAPPED:
        return loadMappedSegment(segmentId, segmentVersion);
      case DISK:
        return loadDiskSegment(segmentId, segmentVersion);
      default:
        throw new AssertionError();
    }
  }

  /**
   * Loads a segment.
   */
  private Segment loadDiskSegment(long segmentId, long segmentVersion) {
    File file = SegmentFile.createSegmentFile(name, storage.directory(), segmentId, segmentVersion);
    Buffer buffer = FileBuffer.allocate(file, Math.min(DEFAULT_BUFFER_SIZE, storage.maxSegmentSize()), Integer.MAX_VALUE);
    SegmentDescriptor descriptor = new SegmentDescriptor(buffer);
    Segment segment = new Segment(buffer.position(SegmentDescriptor.BYTES).slice(), descriptor, createIndex(descriptor), new OffsetPredicate(), serializer.clone(), this);
    LOGGER.debug("Loaded file segment: {} ({})", descriptor.id(), file.getName());
    return segment;
  }

  /**
   * Loads a segment.
   */
  private Segment loadMappedSegment(long segmentId, long segmentVersion) {
    File file = SegmentFile.createSegmentFile(name, storage.directory(), segmentId, segmentVersion);
    Buffer buffer = MappedBuffer.allocate(file, Math.min(DEFAULT_BUFFER_SIZE, storage.maxSegmentSize()), Integer.MAX_VALUE);
    SegmentDescriptor descriptor = new SegmentDescriptor(buffer);
    Segment segment = new Segment(buffer.position(SegmentDescriptor.BYTES).slice(), descriptor, createIndex(descriptor), new OffsetPredicate(), serializer.clone(), this);
    LOGGER.debug("Loaded mapped segment: {} ({})", descriptor.id(), file.getName());
    return segment;
  }

  /**
   * Loads a segment.
   */
  private Segment loadMemorySegment(long segmentId, long segmentVersion) {
    Buffer buffer = HeapBuffer.allocate(Math.min(DEFAULT_BUFFER_SIZE, storage.maxSegmentSize()), Integer.MAX_VALUE);
    SegmentDescriptor descriptor = new SegmentDescriptor(buffer);
    Segment segment = new Segment(buffer.position(SegmentDescriptor.BYTES).slice(), descriptor, createIndex(descriptor), new OffsetPredicate(), serializer.clone(), this);
    LOGGER.debug("Loaded memory segment: {}", descriptor.id());
    return segment;
  }

  /**
   * Creates an in memory segment index.
   */
  private OffsetIndex createIndex(SegmentDescriptor descriptor) {
    return new OffsetIndex(HeapBuffer.allocate(Math.min(DEFAULT_BUFFER_SIZE, descriptor.maxEntries()), OffsetIndex.size(descriptor.maxEntries())));
  }

  /**
   * Loads all segments from disk.
   *
   * @return A collection of segments for the log.
   */
  protected Collection<Segment> loadSegments() {
    // Ensure log directories are created.
    storage.directory().mkdirs();

    TreeMap<Long, Segment> segments = new TreeMap<>();

    // Iterate through all files in the log directory.
    for (File file : storage.directory().listFiles(File::isFile)) {

      // If the file looks like a segment file, attempt to load the segment.
      if (SegmentFile.isSegmentFile(name, file)) {
        SegmentFile segmentFile = new SegmentFile(file);
        SegmentDescriptor descriptor = new SegmentDescriptor(FileBuffer.allocate(file, SegmentDescriptor.BYTES));

        // Valid segments will have been locked. Segments that resulting from failures during log cleaning will be
        // unlocked and should ultimately be deleted from disk.
        if (descriptor.locked()) {

          // Load the segment.
          Segment segment = loadSegment(descriptor.id(), descriptor.version());

          // If a segment with an equal or lower index has already been loaded, ensure this segment is not superseded
          // by the earlier segment. This can occur due to segments being combined during log compaction.
          Map.Entry<Long, Segment> previousEntry = segments.floorEntry(segment.index());
          if (previousEntry != null) {

            // If an existing descriptor exists with a lower index than this segment's first index, check to determine
            // whether this segment's first index is contained in that existing index. If it is, determine which segment
            // should take precedence based on segment versions.
            Segment previousSegment = previousEntry.getValue();

            // If the two segments start at the same index, the segment with the higher version number is used.
            if (previousSegment.index() == segment.index()) {
              if (segment.descriptor().version() > previousSegment.descriptor().version()) {
                LOGGER.debug("Replaced segment {} with newer version: {} ({})", previousSegment.descriptor().id(), segment.descriptor().version(), segmentFile.file().getName());
                segments.remove(previousEntry.getKey());
                previousSegment.close();
                previousSegment.delete();
              } else {
                segment.close();
                segment.delete();
                continue;
              }
            }
            // If the existing segment's entries overlap with the loaded segment's entries, the existing segment always
            // supersedes the loaded segment. Log compaction processes ensure this is always the case.
            else if (previousSegment.index() + previousSegment.length() > segment.index()) {
              segment.close();
              segment.delete();
              continue;
            }
          }

          // Add the segment to the segments list.
          LOGGER.debug("Found segment: {} ({})", segment.descriptor().id(), segmentFile.file().getName());
          segments.put(segment.index(), segment);

          // Ensure any segments later in the log with which this segment overlaps are removed.
          Map.Entry<Long, Segment> nextEntry = segments.higherEntry(segment.index());
          while (nextEntry != null) {
            if (nextEntry.getValue().index() < segment.index() + segment.length()) {
              segments.remove(nextEntry.getKey());
              nextEntry = segments.higherEntry(segment.index());
            } else {
              break;
            }
          }

          descriptor.close();
        }
        // If the segment descriptor wasn't locked, close and delete the descriptor.
        else {
          LOGGER.debug("Deleting unlocked segment: {}-{} ({})", descriptor.id(), descriptor.version(), segmentFile.file().getName());
          descriptor.close();
          descriptor.delete();
        }
      }
    }

    for (Long segmentId : segments.keySet()) {
      Segment segment = segments.get(segmentId);
      Map.Entry<Long, Segment> previousEntry = segments.floorEntry(segmentId - 1);
      if (previousEntry != null) {
        Segment previousSegment = previousEntry.getValue();
        if (previousSegment.index() + previousSegment.length() - 1 < segment.index()) {
          previousSegment.skip(segment.index() - (previousSegment.index() + previousSegment.length()));
        }
      }
    }

    return segments.values();
  }

  @Override
  public void close() {
    segments.values().forEach(s -> {
      LOGGER.debug("Closing segment: {}", s.descriptor().id());
      s.close();
    });
    currentSegment = null;
  }

  @Override
  public String toString() {
    return String.format("%s[directory=%s, segments=%d]", getClass().getSimpleName(), storage.directory(), segments.size());
  }

}
