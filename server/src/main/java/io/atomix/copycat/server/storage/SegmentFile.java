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

import io.atomix.catalyst.util.Assert;

import java.io.File;

/**
 * Segment file utility.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public final class SegmentFile {
  private static final char PART_SEPARATOR = '-';
  private static final char EXTENSION_SEPARATOR = '.';
  private static final String EXTENSION = "log";
  private final File file;

  /**
   * Returns a boolean value indicating whether the given file appears to be a parsable segment file.
   * 
   * @throws NullPointerException if {@code file} is null
   */
  public static boolean isSegmentFile(String name, File file) {
    Assert.notNull(name, "name");
    Assert.notNull(file, "file");
    String fileName = file.getName();
    if (fileName.lastIndexOf(EXTENSION_SEPARATOR) == -1 || fileName.lastIndexOf(PART_SEPARATOR) == -1 || fileName.lastIndexOf(EXTENSION_SEPARATOR) < fileName.lastIndexOf(PART_SEPARATOR) || !fileName.endsWith(EXTENSION))
      return false;

    for (int i = fileName.lastIndexOf(PART_SEPARATOR) + 1; i < fileName.lastIndexOf(EXTENSION_SEPARATOR); i++) {
      if (!Character.isDigit(fileName.charAt(i))) {
        return false;
      }
    }

    if (fileName.lastIndexOf(PART_SEPARATOR, fileName.lastIndexOf(PART_SEPARATOR) - 1) == -1)
      return false;

    for (int i = fileName.lastIndexOf(PART_SEPARATOR, fileName.lastIndexOf(PART_SEPARATOR) - 1) + 1; i < fileName.lastIndexOf(PART_SEPARATOR); i++) {
      if (!Character.isDigit(fileName.charAt(i))) {
        return false;
      }
    }

    return fileName.substring(0, fileName.lastIndexOf(PART_SEPARATOR, fileName.lastIndexOf(PART_SEPARATOR) - 1)).equals(name);
  }

  /**
   * Creates a segment file for the given directory, log name, segment ID, and segment version.
   */
  static File createSegmentFile(String name, File directory, long id, long version) {
    return new File(directory, String.format("%s-%d-%d.log", Assert.notNull(name, "name"), id, version));
  }

  /**
   * @throws IllegalArgumentException if {@code file} is not a valid segment file
   */
  SegmentFile(File file) {
    this.file = file;
  }

  /**
   * Returns the segment file.
   *
   * @return The segment file.
   */
  public File file() {
    return file;
  }

  /**
   * Returns the segment identifier.
   */
  public long id() {
    return Long.valueOf(file.getName().substring(file.getName().lastIndexOf(PART_SEPARATOR, file.getName().lastIndexOf(PART_SEPARATOR) - 1) + 1, file.getName().lastIndexOf(PART_SEPARATOR)));
  }

  /**
   * Returns the segment version.
   */
  public long version() {
    return Long.valueOf(file.getName().substring(file.getName().lastIndexOf(PART_SEPARATOR) + 1, file.getName().lastIndexOf(EXTENSION_SEPARATOR)));
  }

}
