/*
 * Copyright 2016 the original author or authors.
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
package io.atomix.copycat.server.state;

import io.atomix.copycat.server.cluster.Member;

/**
 * Follower appender.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
final class FollowerAppender extends AbstractAppender {

  public FollowerAppender(ServerContext context) {
    super(context);
  }

  /**
   * Sends append requests to assigned passive members.
   */
  public void appendEntries() {
    if (open) {
      for (MemberState member : context.getClusterState().getAssignedPassiveMemberStates()) {
        appendEntries(member);
      }
    }
  }

  @Override
  protected boolean hasMoreEntries(MemberState member) {
    return member.getMember().type() == Member.Type.PASSIVE && member.getNextIndex() <= context.getCommitIndex();
  }

  @Override
  protected void appendEntries(MemberState member) {
    // Prevent recursive, asynchronous appends from being executed if the appender has been closed.
    if (!open)
      return;

    // If the member's current snapshot index is less than the latest snapshot index and the latest snapshot index
    // is less than the nextIndex, send a snapshot request.
    if (context.getSnapshotStore().currentSnapshot() != null
      && context.getSnapshotStore().currentSnapshot().index() >= member.getNextIndex()
      && context.getSnapshotStore().currentSnapshot().index() > member.getSnapshotIndex()) {
      if (canInstall(member)) {
        sendInstallRequest(member, buildInstallRequest(member));
      }
    }
    // If no AppendRequest is already being sent, send an AppendRequest.
    else if (canAppend(member) && hasMoreEntries(member)) {
      sendAppendRequest(member, buildAppendRequest(member, Math.min(context.getCommitIndex(), context.getLog().lastIndex())));
    }
  }

}
