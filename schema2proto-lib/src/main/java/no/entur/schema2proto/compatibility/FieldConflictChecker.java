package no.entur.schema2proto.compatibility;

/*-
 * #%L
 * schema2proto-lib
 * %%
 * Copyright (C) 2019 - 2020 Entur
 * %%
 * Licensed under the EUPL, Version 1.1 or – as soon they will be
 * approved by the European Commission - subsequent versions of the
 * EUPL (the "Licence");
 * 
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 * 
 * http://ec.europa.eu/idabc/eupl5
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 * #L%
 */

import static no.entur.schema2proto.compatibility.ConflictResolverHelper.createBiMap;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.BiMap;
import com.squareup.wire.schema.Field;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.MessageType;
import com.squareup.wire.schema.ProtoFile;

import no.entur.schema2proto.compatibility.protolock.ProtolockField;
import no.entur.schema2proto.compatibility.protolock.ProtolockMessage;

public class FieldConflictChecker {

	private final Logger LOGGER = LoggerFactory.getLogger(FieldConflictChecker.class);
	private boolean failIfRemovedFieldsTriggered;

	public boolean tryResolveFieldConflicts(ProtoFile file, MessageType protoMessage, ProtolockMessage protolockMessage) {
		SortedSet<ProtolockField> lockFields = Collections.unmodifiableSortedSet(getFields(protolockMessage)); // from proto.lock
		SortedSet<ProtolockField> xsdFields = Collections.unmodifiableSortedSet(
				new TreeSet<>(protoMessage.fieldsAndOneOfFields().stream().map(f -> new ProtolockField(f.tag(), f.name())).collect(Collectors.toSet()))); // from
		// parsed
		// Find fields that are new
		Set<ProtolockField> newFieldsInXsd = new TreeSet<>(xsdFields); // from parsed / converted xsd
		newFieldsInXsd.removeAll(lockFields);

		Set<ProtolockField> surplusLockFields = new TreeSet<>(lockFields); // from proto.lock
		surplusLockFields.removeAll(xsdFields);

		if (newFieldsInXsd.isEmpty() && surplusLockFields.isEmpty()) {
			// No mismatch, only minor details
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("No added or removed fields in proto {} {}", file.name(), protoMessage.getName());
			}
		} else if (newFieldsInXsd.isEmpty() && !surplusLockFields.isEmpty()) {
			// Find fields that are removed (make reserved)
			surplusLockFields.stream().forEach(newField -> reserveField(file, protoMessage, newField));

		} else if (!newFieldsInXsd.isEmpty() && surplusLockFields.isEmpty()) {
			// Only new fields from xsd
			newFieldsInXsd.stream().forEach(newField -> LOGGER.debug("Added field in proto {} {} : {}", file.name(), protoMessage.getName(), newField));
		} else {

			// Compute helper maps
			BiMap<String, Integer> xsdFieldsNameToId = createBiMap(newFieldsInXsd);
			Map<Integer, String> xsdFieldsIdToName = xsdFieldsNameToId.inverse();

			BiMap<String, Integer> newFieldsInLockMapNameToId = createBiMap(surplusLockFields);
			Map<Integer, String> newFieldsInLockMapIdToName = newFieldsInLockMapNameToId.inverse();

			TreeSet<String> overlappingNames = new TreeSet<>(xsdFieldsNameToId.keySet());
			overlappingNames.retainAll(newFieldsInLockMapNameToId.keySet());

			TreeSet<Integer> overlappingIds = new TreeSet<>(xsdFieldsNameToId.values());
			overlappingIds.retainAll(newFieldsInLockMapNameToId.values());

			if (!(overlappingIds.isEmpty() && overlappingNames.isEmpty())) {
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("Incompatible changes in proto {} {} , overlapping ids={}, overlapping fieldnames={}", file.name(), protoMessage.getName(),
							overlappingIds, overlappingNames);
				}

				// If an existing field.name (in both proto and protolock) has a new field number, updated field.id to this number. If this number has been used
				// for
				// another field, assign this field to a new id in a "safe" number range
				AtomicInteger nextAvailableFieldNum = findNextAvailableFieldNum(protoMessage, xsdFields, lockFields);

				if (!overlappingIds.isEmpty()) {
					// Check if the new field is using an already allocated id
					int overlappingId = overlappingIds.first();
					String originalFieldNameUsingThisId = newFieldsInLockMapIdToName.get(overlappingId);
					if (originalFieldNameUsingThisId != null) {
						// Find field that has take newFields original number
						String intrudingFieldName = xsdFieldsIdToName.get(overlappingId);
						Optional<Field> intrudingField = getField(protoMessage, intrudingFieldName);
						Optional<Field> existingField = getField(protoMessage, originalFieldNameUsingThisId);

						Integer idFromLockFile = newFieldsInLockMapNameToId.get(intrudingFieldName);
						updateFieldTag(nextAvailableFieldNum, overlappingId, intrudingField, existingField, idFromLockFile);
					}

				} else if (!overlappingNames.isEmpty()) {

					String overlappingName = overlappingNames.first();

					// Check if the new field is using an already allocated name (changed id)
					Integer originalFieldIdForNewName = newFieldsInLockMapNameToId.get(overlappingName);
					if (originalFieldIdForNewName != null) {
						// Find field that has take newFields original number
						Integer intrudingFieldId = xsdFieldsNameToId.get(overlappingName);
						Optional<Field> intrudingField = getField(protoMessage, intrudingFieldId);
						Optional<Field> existingField = getField(protoMessage, originalFieldIdForNewName);

						Integer idFromLockFile = newFieldsInLockMapNameToId.get(overlappingName);
						updateFieldTag(nextAvailableFieldNum, originalFieldIdForNewName, intrudingField, existingField, idFromLockFile);
					}
				}
				tryResolveFieldConflicts(file, protoMessage, protolockMessage);

			} else {
				// If neither overlapping field names nor ids, no problem. Add reserved keyword for removed fields
				surplusLockFields.stream().forEach(newField -> {
					reserveField(file, protoMessage, newField);
					LOGGER.debug("Removed field in proto {}: {}, adding reserved section", file.name(), newField);
				});
			}

		}
		return failIfRemovedFieldsTriggered;

	}

	private void updateFieldTag(AtomicInteger nextAvailableFieldNum, int overlappingId, Optional<Field> intrudingField, Optional<Field> existingField,
			Integer idFromLockFile) {
		intrudingField.ifPresent(x -> {
			if (idFromLockFile != null) {
				x.updateTag(idFromLockFile);
			} else {
				x.updateTag(nextAvailableFieldNum.get());
			}
		});

		existingField.ifPresent(x -> x.updateTag(overlappingId));
	}

	@NotNull
	private AtomicInteger findNextAvailableFieldNum(MessageType e, SortedSet<ProtolockField> xsdFields, SortedSet<ProtolockField> lockFields) {
		AtomicInteger nextAvailableFieldNum = new AtomicInteger(
				xsdFields.stream().max(Comparator.comparing(ProtolockField::getId)).orElse(new ProtolockField(0, null)).getId() + 1);

		// Check that it is not reserved
		while (e.getReserveds().stream().anyMatch(s -> s.matchesTag(nextAvailableFieldNum.get()))
				|| lockFields.stream().anyMatch(s -> s.getId() == nextAvailableFieldNum.get())) {
			nextAvailableFieldNum.incrementAndGet();
		}
		return nextAvailableFieldNum;
	}

	private Optional<Field> getField(MessageType e, String intrudingFieldName) {
		return e.fieldsAndOneOfFields().stream().filter(z -> z.name().equals(intrudingFieldName)).findFirst();
	}

	private Optional<Field> getField(MessageType e, Integer intrudingFieldId) {
		return e.fieldsAndOneOfFields().stream().filter(z -> z.tag() == intrudingFieldId).findFirst();
	}

	private void reserveField(ProtoFile file, MessageType e, ProtolockField newField) {

		String reservationDoc = "Reservation added by schema2proto";
		Location loc = new Location("", "", 0, 0);

		// 2 reservations since field name and id cannot be on the same reservation list
		e.addReserved(reservationDoc, loc, newField.getName());
		e.addReserved(reservationDoc, loc, newField.getId());

		LOGGER.warn(
				"Possible backwards incompatibility detected, must be checked manually! Removed field in proto {}, message {}, field {}, blocking field name and id for future use by adding 'reserved' statement",
				file.name(), e.getName(), newField);
		failIfRemovedFieldsTriggered = true;
	}

	public SortedSet<ProtolockField> getFields(ProtolockMessage protolockMessage) {
		if (protolockMessage != null && protolockMessage.getFields() != null) {
			return new TreeSet<>(Arrays.stream(protolockMessage.getFields()).collect(Collectors.toSet()));
		}

		return new TreeSet<>();
	}

}
