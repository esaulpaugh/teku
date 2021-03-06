/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.artemis.storage.server.rocksdb.serialization;

import com.google.common.primitives.UnsignedLong;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.artemis.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.artemis.datastructures.forkchoice.VoteTracker;
import tech.pegasys.artemis.datastructures.state.BeaconState;
import tech.pegasys.artemis.datastructures.state.BeaconStateImpl;
import tech.pegasys.artemis.datastructures.state.Checkpoint;

public interface RocksDbSerializer<T> {
  RocksDbSerializer<UnsignedLong> UNSIGNED_LONG_SERIALIZER = new UnsignedLongSerializer();
  RocksDbSerializer<Bytes32> BYTES32_SERIALIZER = new BytesSerializer<>(Bytes32::wrap);
  RocksDbSerializer<SignedBeaconBlock> SIGNED_BLOCK_SERIALIZER =
      new SszSerializer<>(SignedBeaconBlock.class);
  RocksDbSerializer<BeaconState> STATE_SERIALIZER = new SszSerializer<>(BeaconStateImpl.class);
  RocksDbSerializer<Checkpoint> CHECKPOINT_SERIALIZER = new SszSerializer<>(Checkpoint.class);
  RocksDbSerializer<VoteTracker> VOTES_SERIALIZER = new SszSerializer<>(VoteTracker.class);

  T deserialize(final byte[] data);

  byte[] serialize(final T value);
}
