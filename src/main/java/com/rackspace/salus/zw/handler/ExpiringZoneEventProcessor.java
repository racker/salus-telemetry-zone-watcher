/*
 * Copyright 2019 Rackspace US, Inc.
 *
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
 */

package com.rackspace.salus.zw.handler;

import static com.rackspace.salus.telemetry.etcd.types.Keys.PTN_ZONE_EXPIRING;
import static com.rackspace.salus.telemetry.etcd.types.Keys.TRACKING_KEY_ZONE_EXPIRING;

import com.rackspace.salus.telemetry.etcd.EtcdUtils;
import com.rackspace.salus.telemetry.etcd.services.ZoneStorage;
import com.rackspace.salus.telemetry.etcd.types.ResolvedZone;
import com.rackspace.salus.zw.services.ZoneStorageListener;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.watch.WatchEvent;
import io.etcd.jetcd.watch.WatchResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import lombok.extern.slf4j.Slf4j;

/**
 * This is used to handle any etcd watcher events that get triggered relating
 * to the expiring zone key prefix.
 */
@Slf4j
public class ExpiringZoneEventProcessor extends ZoneEventProcessor {
  final ByteSequence trackingKey = EtcdUtils.fromString(TRACKING_KEY_ZONE_EXPIRING);

  public ExpiringZoneEventProcessor(ZoneStorageListener listener, ZoneStorage zoneStorage) {
    super(listener, zoneStorage);
  }

  /*
  tenantId and resourceId are being deserialized from the etcd key.
  Since the etcd key is being lowercase'ed the values may be a different case than what is actually being stored in MySQL
   */
  @Override
  public void accept(WatchResponse response) {
    try {
      for (WatchEvent event : response.getEvents()) {

        final String keyStr = event.getKeyValue().getKey().toString(StandardCharsets.UTF_8);
        final Matcher matcher = PTN_ZONE_EXPIRING.matcher(keyStr);

        if (!matcher.matches()) {
          log.warn("Unable to parse expiring event key={}", keyStr);
          continue;
        }

        String resourceId = matcher.group("resourceId");
        final ResolvedZone resolvedZone = ResolvedZone.fromKeyParts(
            matcher.group("tenant"),
            matcher.group("zoneName")
        );

        switch (event.getEventType()) {
          case PUT:
            // no action needed
            break;
          case DELETE:
            // if the lease isn't expired, it means the poller came back up and
            // the expiring entry was removed by the listener before the entry could time out.
            if (zoneStorage.isLeaseExpired(event)) {
              listener.handleExpiredEnvoy(
                  resolvedZone, resourceId, event.getPrevKV().getValue().toString(StandardCharsets.UTF_8));
            }
            break;
          case UNRECOGNIZED:
            log.warn("Unknown expiring watcher event seen by zone watcher");
            break;
        }
      }
    } finally {
      zoneStorage.incrementTrackingKeyVersion(trackingKey);
    }
  }
}