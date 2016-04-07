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
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Segment descriptor test.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
@Test
public class SegmentDescriptorTest {
  private static final File file = new File("descriptor.log");

  /**
   * Tests the segment descriptor builder.
   */
  public void testDescriptorBuilder() {
    SegmentDescriptor descriptor = SegmentDescriptor.builder(FileBuffer.allocate(file, SegmentDescriptor.BYTES))
      .withId(2)
      .withVersion(3)
      .withIndex(1025)
      .withMaxSegmentSize(1024 * 1024)
      .withMaxEntries(2048)
      .build();

    Assert.assertEquals(descriptor.id(), 2);
    Assert.assertEquals(descriptor.version(), 3);
    Assert.assertEquals(descriptor.index(), 1025);
    Assert.assertEquals(descriptor.maxSegmentSize(), 1024 * 1024);
    Assert.assertEquals(descriptor.maxEntries(), 2048);

    Assert.assertEquals(descriptor.updated(), 0);
    long time = System.currentTimeMillis();
    descriptor.update(time);
    Assert.assertEquals(descriptor.updated(), time);

    Assert.assertFalse(descriptor.locked());
    descriptor.lock();
    Assert.assertTrue(descriptor.locked());
  }

  /**
   * Tests persisting the segment descriptor.
   */
  public void testDescriptorPersist() {
    Buffer buffer = FileBuffer.allocate(file, SegmentDescriptor.BYTES);
    SegmentDescriptor descriptor = SegmentDescriptor.builder(buffer)
      .withId(2)
      .withVersion(3)
      .withIndex(1025)
      .withMaxSegmentSize(1024 * 1024)
      .withMaxEntries(2048)
      .build();

    Assert.assertEquals(descriptor.id(), 2);
    Assert.assertEquals(descriptor.version(), 3);
    Assert.assertEquals(descriptor.index(), 1025);
    Assert.assertEquals(descriptor.maxSegmentSize(), 1024 * 1024);
    Assert.assertEquals(descriptor.maxEntries(), 2048);

    buffer.close();

    descriptor = new SegmentDescriptor(FileBuffer.allocate(file, SegmentDescriptor.BYTES));

    Assert.assertEquals(descriptor.id(), 2);
    Assert.assertEquals(descriptor.version(), 3);
    Assert.assertEquals(descriptor.index(), 1025);
    Assert.assertEquals(descriptor.maxSegmentSize(), 1024 * 1024);
  }

  /**
   * Tests copying the segment descriptor.
   */
  public void testDescriptorCopy() {
    SegmentDescriptor descriptor = SegmentDescriptor.builder()
      .withId(2)
      .withVersion(3)
      .withIndex(1025)
      .withMaxSegmentSize(1024 * 1024)
      .withMaxEntries(2048)
      .build();

    long time = System.currentTimeMillis();
    descriptor.update(time);
    descriptor.lock();

    descriptor = descriptor.copyTo(FileBuffer.allocate(file, SegmentDescriptor.BYTES));

    Assert.assertEquals(descriptor.id(), 2);
    Assert.assertEquals(descriptor.version(), 3);
    Assert.assertEquals(descriptor.index(), 1025);
    Assert.assertEquals(descriptor.maxSegmentSize(), 1024 * 1024);
    Assert.assertEquals(descriptor.maxEntries(), 2048);
    Assert.assertEquals(descriptor.updated(), time);
    Assert.assertTrue(descriptor.locked());
  }

  /**
   * Deletes the descriptor file.
   */
  @AfterMethod
  public void deleteDescriptor() throws IOException {
    if (Files.exists(file.toPath())) {
      Files.delete(file.toPath());
    }
  }

}
