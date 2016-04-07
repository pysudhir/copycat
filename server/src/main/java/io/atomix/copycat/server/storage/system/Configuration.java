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
package io.atomix.copycat.server.storage.system;

import io.atomix.catalyst.util.Assert;
import io.atomix.copycat.server.cluster.Member;

import java.util.Collection;

/**
 * Represents a persisted server configuration.
 * <p>
 * This class represents a cluster configuration stored on disk. Configurations are managed by
 * a {@link MetaStore}, stored on disk when {@link io.atomix.copycat.server.cluster.Cluster Cluster}
 * configurations change, and loaded from disk on server startup.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class Configuration {
  private final long index;
  private final long term;
  private final long time;
  private final Collection<Member> members;

  public Configuration(long index, long term, long time, Collection<Member> members) {
    this.index = index;
    this.term = term;
    this.time = Assert.argNot(time, time <= 0, "time must be positive");
    this.members = Assert.notNull(members, "members");
  }

  /**
   * Returns the configuration index.
   * <p>
   * The index is the index of the {@link io.atomix.copycat.server.storage.entry.ConfigurationEntry ConfigurationEntry}
   * which resulted in this configuration.
   *
   * @return The configuration index.
   */
  public long index() {
    return index;
  }

  /**
   * Returns the configuration term.
   * <p>
   * The term is the term of the leader at the time the configuration change was committed.
   *
   * @return The configuration term.
   */
  public long term() {
    return term;
  }

  /**
   * Returns the configuration time.
   *
   * @return The time at which the configuration was committed.
   */
  public long time() {
    return time;
  }

  /**
   * Returns the cluster membership for this configuration.
   *
   * @return The cluster membership.
   */
  public Collection<Member> members() {
    return members;
  }

  @Override
  public String toString() {
    return String.format("%s[index=%d, time=%d, members=%s]", getClass().getSimpleName(), index, time, members);
  }

}
