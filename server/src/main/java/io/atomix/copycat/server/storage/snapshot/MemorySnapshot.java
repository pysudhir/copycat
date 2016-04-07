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
 * limitations under the License
 */
package io.atomix.copycat.server.storage.snapshot;

import io.atomix.catalyst.buffer.HeapBuffer;
import io.atomix.catalyst.util.Assert;

/**
 * In-memory snapshot backed by a {@link HeapBuffer}.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
final class MemorySnapshot extends Snapshot {
  private final HeapBuffer buffer;
  private final SnapshotDescriptor descriptor;
  private final SnapshotStore store;

  MemorySnapshot(HeapBuffer buffer, SnapshotDescriptor descriptor, SnapshotStore store) {
    super(store);
    buffer.mark();
    this.buffer = Assert.notNull(buffer, "buffer");
    this.buffer.position(SnapshotDescriptor.BYTES).mark();
    this.descriptor = Assert.notNull(descriptor, "descriptor");
    this.store = Assert.notNull(store, "store");
  }

  @Override
  public long index() {
    return descriptor.index();
  }

  @Override
  public long timestamp() {
    return descriptor.timestamp();
  }

  @Override
  public SnapshotWriter writer() {
    checkWriter();
    return new SnapshotWriter(buffer.reset().slice(), this, store.serializer());
  }

  @Override
  protected void closeWriter(SnapshotWriter writer) {
    buffer.skip(writer.buffer.position()).mark();
    super.closeWriter(writer);
  }

  @Override
  public synchronized SnapshotReader reader() {
    return openReader(new SnapshotReader(buffer.reset().slice(), this, store.serializer()), descriptor);
  }

  @Override
  public Snapshot complete() {
    descriptor.lock();
    buffer.flip().position(SnapshotDescriptor.BYTES).mark();
    return super.complete();
  }

  @Override
  public void close() {
    buffer.close();
  }

  @Override
  public String toString() {
    return String.format("%s[index=%d]", getClass().getSimpleName(), descriptor.index());
  }

}
