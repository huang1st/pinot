/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.broker.routing.segmentpruner;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.pinot.common.metadata.segment.SegmentPartitionMetadata;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.common.request.Expression;
import org.apache.pinot.common.request.Function;
import org.apache.pinot.common.request.Identifier;
import org.apache.pinot.segment.spi.partition.PartitionFunction;
import org.apache.pinot.segment.spi.partition.PartitionFunctionFactory;
import org.apache.pinot.segment.spi.partition.metadata.ColumnPartitionMetadata;
import org.apache.pinot.spi.utils.CommonConstants.Segment;
import org.apache.pinot.sql.FilterKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The {@code SinglePartitionColumnSegmentPruner} prunes segments based on their partition metadata stored in ZK. The
 * pruner supports queries with filter (or nested filter) of EQUALITY and IN predicates.
 */
public class SinglePartitionColumnSegmentPruner implements SegmentPruner {
  private static final Logger LOGGER = LoggerFactory.getLogger(SinglePartitionColumnSegmentPruner.class);
  private static final PartitionInfo INVALID_PARTITION_INFO = new PartitionInfo(null, null);

  private final String _tableNameWithType;
  private final String _partitionColumn;
  private final Map<String, PartitionInfo> _partitionInfoMap = new ConcurrentHashMap<>();

  public SinglePartitionColumnSegmentPruner(String tableNameWithType, String partitionColumn) {
    _tableNameWithType = tableNameWithType;
    _partitionColumn = partitionColumn;
  }

  @Override
  public void init(IdealState idealState, ExternalView externalView, List<String> onlineSegments,
      List<ZNRecord> znRecords) {
    // Bulk load partition info for all online segments
    for (int idx = 0; idx < onlineSegments.size(); idx++) {
      String segment = onlineSegments.get(idx);
      PartitionInfo partitionInfo = extractPartitionInfoFromSegmentZKMetadataZNRecord(segment, znRecords.get(idx));
      if (partitionInfo != null) {
        _partitionInfoMap.put(segment, partitionInfo);
      }
    }
  }

  /**
   * NOTE: Returns {@code null} when the ZNRecord is missing (could be transient Helix issue). Returns
   *       {@link #INVALID_PARTITION_INFO} when the segment does not have valid partition metadata in its ZK metadata,
   *       in which case we won't retry later.
   */
  @Nullable
  private PartitionInfo extractPartitionInfoFromSegmentZKMetadataZNRecord(String segment, @Nullable ZNRecord znRecord) {
    if (znRecord == null) {
      LOGGER.warn("Failed to find segment ZK metadata for segment: {}, table: {}", segment, _tableNameWithType);
      return null;
    }

    String partitionMetadataJson = znRecord.getSimpleField(Segment.PARTITION_METADATA);
    if (partitionMetadataJson == null) {
      LOGGER.warn("Failed to find segment partition metadata for segment: {}, table: {}", segment, _tableNameWithType);
      return INVALID_PARTITION_INFO;
    }

    SegmentPartitionMetadata segmentPartitionMetadata;
    try {
      segmentPartitionMetadata = SegmentPartitionMetadata.fromJsonString(partitionMetadataJson);
    } catch (Exception e) {
      LOGGER.warn("Caught exception while extracting segment partition metadata for segment: {}, table: {}", segment,
          _tableNameWithType, e);
      return INVALID_PARTITION_INFO;
    }

    ColumnPartitionMetadata columnPartitionMetadata =
        segmentPartitionMetadata.getColumnPartitionMap().get(_partitionColumn);
    if (columnPartitionMetadata == null) {
      LOGGER.warn("Failed to find column partition metadata for column: {}, segment: {}, table: {}", _partitionColumn,
          segment, _tableNameWithType);
      return INVALID_PARTITION_INFO;
    }

    return new PartitionInfo(PartitionFunctionFactory.getPartitionFunction(columnPartitionMetadata.getFunctionName(),
        columnPartitionMetadata.getNumPartitions(), columnPartitionMetadata.getFunctionConfig()),
        columnPartitionMetadata.getPartitions());
  }

  @Override
  public synchronized void onAssignmentChange(IdealState idealState, ExternalView externalView,
      Set<String> onlineSegments, List<String> pulledSegments, List<ZNRecord> znRecords) {
    // NOTE: We don't update all the segment ZK metadata for every external view change, but only the new added/removed
    //       ones. The refreshed segment ZK metadata change won't be picked up.
    for (int idx = 0; idx < pulledSegments.size(); idx++) {
      String segment = pulledSegments.get(idx);
      ZNRecord znRecord = znRecords.get(idx);
      _partitionInfoMap.computeIfAbsent(segment, k -> extractPartitionInfoFromSegmentZKMetadataZNRecord(k, znRecord));
    }
    _partitionInfoMap.keySet().retainAll(onlineSegments);
  }

  @Override
  public synchronized void refreshSegment(String segment, @Nullable ZNRecord znRecord) {
    PartitionInfo partitionInfo = extractPartitionInfoFromSegmentZKMetadataZNRecord(segment, znRecord);
    if (partitionInfo != null) {
      _partitionInfoMap.put(segment, partitionInfo);
    } else {
      _partitionInfoMap.remove(segment);
    }
  }

  @Override
  public Set<String> prune(BrokerRequest brokerRequest, Set<String> segments) {
    Expression filterExpression = brokerRequest.getPinotQuery().getFilterExpression();
    if (filterExpression == null) {
      return segments;
    }
    Set<String> selectedSegments = new HashSet<>();
    for (String segment : segments) {
      PartitionInfo partitionInfo = _partitionInfoMap.get(segment);
      if (partitionInfo == null || partitionInfo == INVALID_PARTITION_INFO || isPartitionMatch(filterExpression,
          partitionInfo)) {
        selectedSegments.add(segment);
      }
    }
    return selectedSegments;
  }

  private boolean isPartitionMatch(Expression filterExpression, PartitionInfo partitionInfo) {
    Function function = filterExpression.getFunctionCall();
    FilterKind filterKind = FilterKind.valueOf(function.getOperator());
    List<Expression> operands = function.getOperands();
    switch (filterKind) {
      case AND:
        for (Expression child : operands) {
          if (!isPartitionMatch(child, partitionInfo)) {
            return false;
          }
        }
        return true;
      case OR:
        for (Expression child : operands) {
          if (isPartitionMatch(child, partitionInfo)) {
            return true;
          }
        }
        return false;
      case EQUALS: {
        Identifier identifier = operands.get(0).getIdentifier();
        if (identifier != null && identifier.getName().equals(_partitionColumn)) {
          return partitionInfo._partitions.contains(
              partitionInfo._partitionFunction.getPartition(operands.get(1).getLiteral().getFieldValue().toString()));
        } else {
          return true;
        }
      }
      case IN: {
        Identifier identifier = operands.get(0).getIdentifier();
        if (identifier != null && identifier.getName().equals(_partitionColumn)) {
          int numOperands = operands.size();
          for (int i = 1; i < numOperands; i++) {
            if (partitionInfo._partitions.contains(partitionInfo._partitionFunction.getPartition(
                operands.get(i).getLiteral().getFieldValue().toString()))) {
              return true;
            }
          }
          return false;
        } else {
          return true;
        }
      }
      default:
        return true;
    }
  }

  private static class PartitionInfo {
    final PartitionFunction _partitionFunction;
    final Set<Integer> _partitions;

    PartitionInfo(PartitionFunction partitionFunction, Set<Integer> partitions) {
      _partitionFunction = partitionFunction;
      _partitions = partitions;
    }
  }
}
