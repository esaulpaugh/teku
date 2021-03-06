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

package tech.pegasys.artemis.networking.eth2.gossip.topics.validation;

import static com.google.common.primitives.UnsignedLong.ONE;
import static com.google.common.primitives.UnsignedLong.ZERO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static tech.pegasys.artemis.core.CommitteeAssignmentUtil.get_committee_assignment;
import static tech.pegasys.artemis.datastructures.util.BeaconStateUtil.compute_epoch_at_slot;
import static tech.pegasys.artemis.datastructures.util.CommitteeUtil.getAggregatorModulo;
import static tech.pegasys.artemis.datastructures.util.CommitteeUtil.isAggregator;
import static tech.pegasys.artemis.networking.eth2.gossip.topics.validation.ValidationResult.INVALID;
import static tech.pegasys.artemis.networking.eth2.gossip.topics.validation.ValidationResult.SAVED_FOR_FUTURE;
import static tech.pegasys.artemis.networking.eth2.gossip.topics.validation.ValidationResult.VALID;

import com.google.common.eventbus.EventBus;
import com.google.common.primitives.UnsignedLong;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.artemis.bls.BLSKeyPair;
import tech.pegasys.artemis.core.AggregateGenerator;
import tech.pegasys.artemis.core.AttestationGenerator;
import tech.pegasys.artemis.datastructures.blocks.BeaconBlockAndState;
import tech.pegasys.artemis.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.artemis.datastructures.operations.Attestation;
import tech.pegasys.artemis.datastructures.operations.AttestationData;
import tech.pegasys.artemis.datastructures.operations.SignedAggregateAndProof;
import tech.pegasys.artemis.datastructures.state.CommitteeAssignment;
import tech.pegasys.artemis.datastructures.util.BeaconStateUtil;
import tech.pegasys.artemis.datastructures.util.DataStructureUtil;
import tech.pegasys.artemis.datastructures.util.MockStartValidatorKeyPairFactory;
import tech.pegasys.artemis.statetransition.BeaconChainUtil;
import tech.pegasys.artemis.storage.client.MemoryOnlyRecentChainData;
import tech.pegasys.artemis.storage.client.RecentChainData;

/**
 * The following validations MUST pass before forwarding the signed_aggregate_and_proof on the
 * network. (We define the following for convenience -- aggregate_and_proof =
 * signed_aggregate_and_proof.message and aggregate = aggregate_and_proof.aggregate)
 *
 * <p>aggregate.data.slot is within the last ATTESTATION_PROPAGATION_SLOT_RANGE slots (with a
 * MAXIMUM_GOSSIP_CLOCK_DISPARITY allowance) -- i.e. aggregate.data.slot +
 * ATTESTATION_PROPAGATION_SLOT_RANGE >= current_slot >= aggregate.data.slot (a client MAY queue
 * future aggregates for processing at the appropriate slot).
 *
 * <p>The aggregate attestation defined by hash_tree_root(aggregate) has not already been seen (via
 * aggregate gossip, within a block, or through the creation of an equivalent aggregate locally).
 *
 * <p>The aggregate is the first valid aggregate received for the aggregator with index
 * aggregate_and_proof.aggregator_index for the slot aggregate.data.slot.
 *
 * <p>The block being voted for (aggregate.data.beacon_block_root) passes validation.
 *
 * <p>aggregate_and_proof.selection_proof selects the validator as an aggregator for the slot --
 * i.e. is_aggregator(state, aggregate.data.slot, aggregate.data.index,
 * aggregate_and_proof.selection_proof) returns True.
 *
 * <p>The aggregator's validator index is within the aggregate's committee -- i.e.
 * aggregate_and_proof.aggregator_index in get_attesting_indices(state, aggregate.data,
 * aggregate.aggregation_bits).
 *
 * <p>The aggregate_and_proof.selection_proof is a valid signature of the aggregate.data.slot by the
 * validator with index aggregate_and_proof.aggregator_index.
 *
 * <p>The aggregator signature, signed_aggregate_and_proof.signature, is valid.
 *
 * <p>The signature of aggregate is valid.
 */
class SignedAggregateAndProofValidatorTest {

  private static final List<BLSKeyPair> VALIDATOR_KEYS =
      new MockStartValidatorKeyPairFactory().generateKeyPairs(0, 1024);
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil();
  private final RecentChainData recentChainData = MemoryOnlyRecentChainData.create(new EventBus());
  private final BeaconChainUtil beaconChainUtil =
      BeaconChainUtil.create(recentChainData, VALIDATOR_KEYS, false);
  private final AggregateGenerator generator =
      new AggregateGenerator(beaconChainUtil.getValidatorKeys());
  private final AttestationValidator attestationValidator = mock(AttestationValidator.class);

  private final SignedAggregateAndProofValidator validator =
      new SignedAggregateAndProofValidator(attestationValidator, recentChainData);

  @BeforeAll
  public static void init() {
    BeaconStateUtil.BLS_VERIFY_DEPOSIT = false;
  }

  @AfterAll
  public static void reset() {
    BeaconStateUtil.BLS_VERIFY_DEPOSIT = true;
  }

  @BeforeEach
  public void setUp() {
    beaconChainUtil.initializeStorage();
  }

  @Test
  public void shouldReturnValidForValidAggregate() {
    final BeaconBlockAndState chainHead = recentChainData.getBestBlockAndState().orElseThrow();
    final SignedAggregateAndProof aggregate = generator.validAggregateAndProof(chainHead);
    whenAttestationIsValid(aggregate);
    assertThat(validator.validate(aggregate)).isEqualTo(VALID);
  }

  @Test
  public void shouldRejectWhenAttestationValidatorRejects() {
    final SignedAggregateAndProof aggregate =
        generator.validAggregateAndProof(recentChainData.getBestBlockAndState().orElseThrow());
    when(attestationValidator.singleOrAggregateAttestationChecks(
            aggregate.getMessage().getAggregate()))
        .thenReturn(INVALID);

    assertThat(validator.validate(aggregate)).isEqualTo(INVALID);
  }

  @Test
  public void shouldSaveForFutureWhenAttestationValidatorSavesForFuture() {
    final SignedAggregateAndProof aggregate =
        generator.validAggregateAndProof(recentChainData.getBestBlockAndState().orElseThrow());
    when(attestationValidator.singleOrAggregateAttestationChecks(
            aggregate.getMessage().getAggregate()))
        .thenReturn(SAVED_FOR_FUTURE);

    assertThat(validator.validate(aggregate)).isEqualTo(SAVED_FOR_FUTURE);
  }

  @Test
  public void shouldSaveForFutureWhenStateIsNotAvailable() throws Exception {
    final SignedBlockAndState target = beaconChainUtil.createBlockAndStateAtSlot(ONE, true);
    final SignedAggregateAndProof aggregate = generator.validAggregateAndProof(target.toUnsigned());
    when(attestationValidator.singleOrAggregateAttestationChecks(
            aggregate.getMessage().getAggregate()))
        .thenReturn(SAVED_FOR_FUTURE);

    assertThat(validator.validate(aggregate)).isEqualTo(SAVED_FOR_FUTURE);
  }

  @Test
  public void shouldRejectWhenAttestationValidatorSavesForFutureAndAggregateChecksFail() {
    final SignedAggregateAndProof aggregate =
        generator
            .generator()
            .blockAndState(recentChainData.getBestBlockAndState().orElseThrow())
            .aggregatorIndex(ONE)
            .selectionProof(dataStructureUtil.randomSignature())
            .generate();
    when(attestationValidator.singleOrAggregateAttestationChecks(
            aggregate.getMessage().getAggregate()))
        .thenReturn(SAVED_FOR_FUTURE);

    assertThat(validator.validate(aggregate)).isEqualTo(INVALID);
  }

  @Test
  public void shouldOnlyAcceptFirstAggregateWithSameSlotAndAggregatorIndex() {
    final BeaconBlockAndState chainHead = recentChainData.getBestBlockAndState().orElseThrow();
    final SignedAggregateAndProof aggregateAndProof1 = generator.validAggregateAndProof(chainHead);

    final List<Attestation> aggregatesForSlot =
        AttestationGenerator.groupAndAggregateAttestations(
            generator
                .getAttestationGenerator()
                .getAttestationsForSlot(
                    chainHead.getState(), chainHead.getBlock(), chainHead.getSlot()));
    final Attestation aggregate2 =
        aggregatesForSlot.stream()
            .filter(attestation -> hasSameCommitteeIndex(aggregateAndProof1, attestation))
            .findFirst()
            .orElseThrow();
    final SignedAggregateAndProof aggregateAndProof2 =
        generator.generator().blockAndState(chainHead).aggregate(aggregate2).generate();
    whenAttestationIsValid(aggregateAndProof1);
    whenAttestationIsValid(aggregateAndProof2);

    // Sanity check
    assertThat(aggregateAndProof1.getMessage().getAggregate()).isNotEqualTo(aggregate2);
    assertThat(aggregateAndProof1).isNotEqualTo(aggregateAndProof2);

    assertThat(validator.validate(aggregateAndProof1)).isEqualTo(VALID);
    assertThat(validator.validate(aggregateAndProof2)).isEqualTo(INVALID);
  }

  @Test
  public void shouldAcceptAggregateWithSameSlotAndDifferentAggregatorIndex() {
    final BeaconBlockAndState chainHead = recentChainData.getBestBlockAndState().orElseThrow();
    final SignedAggregateAndProof aggregateAndProof1 = generator.validAggregateAndProof(chainHead);

    final List<Attestation> aggregatesForSlot =
        AttestationGenerator.groupAndAggregateAttestations(
            generator
                .getAttestationGenerator()
                .getAttestationsForSlot(
                    chainHead.getState(), chainHead.getBlock(), chainHead.getSlot()));
    final Attestation aggregate2 =
        aggregatesForSlot.stream()
            .filter(attestation -> !hasSameCommitteeIndex(aggregateAndProof1, attestation))
            .findFirst()
            .orElseThrow();
    final SignedAggregateAndProof aggregateAndProof2 =
        generator.generator().blockAndState(chainHead).aggregate(aggregate2).generate();
    whenAttestationIsValid(aggregateAndProof1);
    whenAttestationIsValid(aggregateAndProof2);

    assertThat(validator.validate(aggregateAndProof1)).isEqualTo(VALID);
    assertThat(validator.validate(aggregateAndProof2)).isEqualTo(VALID);
  }

  @Test
  public void shouldAcceptAggregateWithSameAggregatorIndexAndDifferentSlot() {
    beaconChainUtil.setSlot(ONE);
    final BeaconBlockAndState chainHead = recentChainData.getBestBlockAndState().orElseThrow();

    final SignedAggregateAndProof aggregateAndProof1 =
        generator.validAggregateAndProof(chainHead, ZERO);

    final UnsignedLong aggregatorIndex = aggregateAndProof1.getMessage().getIndex();
    final CommitteeAssignment epochOneCommitteeAssignment =
        getCommitteeAssignment(chainHead, aggregatorIndex.intValue(), ONE);
    final SignedAggregateAndProof aggregateAndProof2 =
        generator
            .generator()
            .blockAndState(chainHead)
            .committeeIndex(epochOneCommitteeAssignment.getCommitteeIndex())
            .slot(epochOneCommitteeAssignment.getSlot())
            .aggregatorIndex(aggregatorIndex)
            .generate();
    whenAttestationIsValid(aggregateAndProof1);
    whenAttestationIsValid(aggregateAndProof2);

    // Sanity check
    assertThat(aggregateAndProof1.getMessage().getAggregate().getData().getSlot())
        .isNotEqualTo(aggregateAndProof2.getMessage().getAggregate().getData().getSlot());
    assertThat(aggregateAndProof1.getMessage().getIndex())
        .isEqualTo(aggregateAndProof2.getMessage().getIndex());

    assertThat(validator.validate(aggregateAndProof1)).isEqualTo(VALID);
    assertThat(validator.validate(aggregateAndProof2)).isEqualTo(VALID);
  }

  @Test
  public void shouldRejectAggregateWhenSelectionProofDoesNotSelectAsAggregator() {
    final BeaconBlockAndState chainHead = recentChainData.getBestBlockAndState().orElseThrow();
    int aggregatorIndex = 3;
    final CommitteeAssignment committeeAssignment =
        getCommitteeAssignment(chainHead, aggregatorIndex, ZERO);
    final SignedAggregateAndProof aggregate =
        generator
            .generator()
            .blockAndState(chainHead)
            .aggregatorIndex(UnsignedLong.valueOf(aggregatorIndex))
            .committeeIndex(committeeAssignment.getCommitteeIndex())
            .slot(committeeAssignment.getSlot())
            .generate();
    whenAttestationIsValid(aggregate);
    // Sanity check
    final int aggregatorModulo = getAggregatorModulo(committeeAssignment.getCommittee().size());
    assertThat(aggregatorModulo).isGreaterThan(1);
    assertThat(isAggregator(aggregate.getMessage().getSelection_proof(), aggregatorModulo))
        .isFalse();

    assertThat(validator.validate(aggregate)).isEqualTo(INVALID);
  }

  @Test
  public void shouldRejectIfAggregatorIndexIsNotWithinTheCommittee() {
    final BeaconBlockAndState chainHead = recentChainData.getBestBlockAndState().orElseThrow();
    final int aggregatorIndex = 60;
    final SignedAggregateAndProof aggregate =
        generator
            .generator()
            .blockAndState(chainHead)
            .aggregatorIndex(UnsignedLong.valueOf(aggregatorIndex))
            .generate();
    whenAttestationIsValid(aggregate);
    // Sanity check aggregator is not in the committee
    final AttestationData attestationData = aggregate.getMessage().getAggregate().getData();
    final CommitteeAssignment committeeAssignment =
        getCommitteeAssignment(
            chainHead, aggregatorIndex, compute_epoch_at_slot(chainHead.getSlot()));
    if (committeeAssignment.getCommitteeIndex().equals(attestationData.getIndex())
        && committeeAssignment.getSlot().equals(attestationData.getSlot())) {
      fail("Aggregator was in the committee");
    }

    assertThat(validator.validate(aggregate)).isEqualTo(INVALID);
  }

  @Test
  public void shouldRejectIfSelectionProofIsNotAValidSignatureOfAggregatorIndex() {
    final SignedAggregateAndProof aggregate =
        generator
            .generator()
            .blockAndState(recentChainData.getBestBlockAndState().orElseThrow())
            .aggregatorIndex(ONE)
            .selectionProof(dataStructureUtil.randomSignature())
            .generate();
    whenAttestationIsValid(aggregate);

    assertThat(validator.validate(aggregate)).isEqualTo(INVALID);
  }

  @Test
  public void shouldRejectIfAggregateAndProofSignatureIsNotValid() {
    final SignedAggregateAndProof validAggregate =
        generator.validAggregateAndProof(recentChainData.getBestBlockAndState().orElseThrow());
    final SignedAggregateAndProof invalidAggregate =
        new SignedAggregateAndProof(
            validAggregate.getMessage(), dataStructureUtil.randomSignature());
    whenAttestationIsValid(invalidAggregate);
    whenAttestationIsValid(validAggregate);

    assertThat(validator.validate(invalidAggregate)).isEqualTo(INVALID);
    assertThat(validator.validate(validAggregate)).isEqualTo(VALID);
  }

  private boolean hasSameCommitteeIndex(
      final SignedAggregateAndProof aggregateAndProof, final Attestation attestation) {
    return attestation
        .getData()
        .getIndex()
        .equals(aggregateAndProof.getMessage().getAggregate().getData().getIndex());
  }

  private void whenAttestationIsValid(final SignedAggregateAndProof aggregate) {
    when(attestationValidator.singleOrAggregateAttestationChecks(
            aggregate.getMessage().getAggregate()))
        .thenReturn(VALID);
  }

  private CommitteeAssignment getCommitteeAssignment(
      final BeaconBlockAndState chainHead, final int aggregatorIndex, final UnsignedLong epoch) {
    return get_committee_assignment(chainHead.getState(), epoch, aggregatorIndex).orElseThrow();
  }
}
