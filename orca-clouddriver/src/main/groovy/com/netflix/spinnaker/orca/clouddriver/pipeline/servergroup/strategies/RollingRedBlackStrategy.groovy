/*
 * Copyright 2017 Netflix, Inc.
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
package com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.strategies

import com.netflix.spinnaker.orca.clouddriver.pipeline.cluster.ScaleDownClusterStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.CloneServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.CreateServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.DisableServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.PinServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.ResizeServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.DetermineTargetServerGroupStage
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.TargetServerGroup
import com.netflix.spinnaker.orca.front50.pipeline.PipelineStage
import com.netflix.spinnaker.orca.kato.pipeline.support.ResizeStrategy
import com.netflix.spinnaker.orca.kato.pipeline.support.StageData
import com.netflix.spinnaker.orca.pipeline.WaitStage
import com.netflix.spinnaker.orca.pipeline.model.Stage
import com.netflix.spinnaker.orca.pipeline.model.SyntheticStageOwner
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Component

import java.util.concurrent.TimeUnit

import static com.netflix.spinnaker.orca.pipeline.StageDefinitionBuilder.newStage
import static com.netflix.spinnaker.orca.kato.pipeline.strategy.Strategy.ROLLING_RED_BLACK

@Slf4j
@Component
class RollingRedBlackStrategy implements Strategy, ApplicationContextAware {
  final String name = ROLLING_RED_BLACK.key

  @Autowired
  DisableServerGroupStage disableServerGroupStage

  @Autowired
  ResizeServerGroupStage resizeServerGroupStage

  @Autowired
  WaitStage waitStage

  @Autowired(required = false)
  PipelineStage pipelineStage

  @Autowired
  DetermineTargetServerGroupStage determineTargetServerGroupStage

  @Autowired
  ScaleDownClusterStage scaleDownClusterStage

  @Override
  List<Stage> composeBeforeStages(Stage stage) {
    if (!pipelineStage) {
      throw new IllegalStateException("Rolling red/black cannot be run without front50 enabled. Please set 'front50.enabled: true' in your orca config.")
    }

    def stageData = stage.mapTo(RollingRedBlackStageData)
    if (stage.context.useSourceCapacity) {
      stage.context.useSourceCapacity = false
    }

    if (stageData.targetSize) {
      stage.context.targetSize = 0
    }

    // we expect a capacity object if a fixed capacity has been requested or as a fallback value when we are copying
    // the capacity from the current server group
    def savedCapacity = stage.context.savedCapacity ?: stage.context.capacity?.clone()
    stage.context.savedCapacity = savedCapacity

    // FIXME: this clobbers the input capacity value (if any). Should find a better way to request a new asg of size 0
    stage.context.capacity = [
      min    : 0,
      max    : 0,
      desired: 0
    ]

    return Collections.emptyList()
  }

  @Override
  List<Stage> composeAfterStages(Stage stage) {
    def stages = []
    def stageData = stage.mapTo(RollingRedBlackStageData)
    def cleanupConfig = AbstractDeployStrategyStage.CleanupConfig.fromStage(stage)

    Map baseContext = [
      (cleanupConfig.location.singularType()): cleanupConfig.location.value,
      cluster                                : cleanupConfig.cluster,
      moniker                                : cleanupConfig.moniker,
      credentials                            : cleanupConfig.account,
      cloudProvider                          : cleanupConfig.cloudProvider,
    ]

    // we expect a capacity object if a fixed capacity has been requested or as a fallback value when we are copying
    // the capacity from the current server group
    def savedCapacity = stage.context.savedCapacity

    def targetPercentages = stageData.getTargetPercentages()
    if (targetPercentages.isEmpty() || targetPercentages[-1] != 100) {
      targetPercentages.add(100)
    }

    def findContext = baseContext + [
      target        : TargetServerGroup.Params.Target.current_asg_dynamic,
      targetLocation: cleanupConfig.location,
    ]

    // Get source ASG from prior determineSourceServerGroupTask
    def source = null

    try {
      source = lookupSourceServerGroup(stage)
    } catch (Exception e) {
      // This probably means there was no parent CreateServerGroup stage - which should never happen
      throw new IllegalStateException("Failed to determine source server group from parent stage while planning RRB flow", e)
    }

    stages << newStage(
      stage.execution,
      determineTargetServerGroupStage.type,
      "Determine Deployed Server Group",
      findContext,
      stage,
      SyntheticStageOwner.STAGE_AFTER
    )

    if (source == null) {
      log.warn("no source server group -- will perform RRB to exact fallback capacity $savedCapacity with no disableCluster or scaleDownCluster stages")
    } else {
      def resizeContext = baseContext
      resizeContext.putAll([
        serverGroupName   : source.serverGroupName,
        action            : ResizeStrategy.ResizeAction.scale_to_server_group,
        source            : source,
        useNameAsLabel    : true,     // hint to deck that it should _not_ override the name
        pinMinimumCapacity: true
      ])

      stages << newStage(
        stage.execution,
        PinServerGroupStage.TYPE,
        "Pin ${resizeContext.serverGroupName}",
        resizeContext,
        stage,
        SyntheticStageOwner.STAGE_AFTER
      )
    }

    // java .forEach rather than groovy .each, since the nested .each closure sometimes omits parent context
    targetPercentages.forEach({ p ->
      def resizeContext = baseContext + [
        target              : TargetServerGroup.Params.Target.current_asg_dynamic,
        targetLocation      : cleanupConfig.location,
        scalePct            : p,
        pinCapacity         : p < 100,  // if p < 100, capacity should be pinned (min == max == desired)
        unpinMinimumCapacity: p == 100, // if p == 100, min capacity should be restored to the original unpinned value from source
        pinMinimumCapacity  : p < 100,  // pinMinimumCapacity should be false when unpinMinimumCapacity is true
        useNameAsLabel      : true,     // hint to deck that it should _not_ override the name
        targetHealthyDeployPercentage: stage.context.targetHealthyDeployPercentage
      ]

      if (source) {
        resizeContext = resizeContext + [
          action: ResizeStrategy.ResizeAction.scale_to_server_group,
          source: source
        ]
      } else {
        resizeContext = resizeContext + [
          action: ResizeStrategy.ResizeAction.scale_exact,
        ]
        resizeContext.capacity = savedCapacity // will scale to a percentage of that static capacity
      }

      log.info("Adding `Grow to $p% of Desired Size` stage with context $resizeContext [executionId=${stage.execution.id}]")

      def resizeStage = newStage(
        stage.execution,
        resizeServerGroupStage.type,
        "Grow to $p% of Desired Size",
        resizeContext,
        stage,
        SyntheticStageOwner.STAGE_AFTER
      )
      stages << resizeStage

      // an expression to grab newly deployed server group at runtime (ie. the server group being resized up)
      def deployedServerGroupName = '${' + "#stage('${resizeStage.id}')['context']['asgName']" + '}'.toString()
      stages.addAll(getBeforeCleanupStages(stage, stageData, cleanupConfig, source?.serverGroupName, deployedServerGroupName, p))

      // only generate the "disable p% of traffic" stages if we have something to disable
      if (source) {
        def disableContext = baseContext + [
          desiredPercentage : p,
          serverGroupName   : source.serverGroupName
        ]

        log.info("Adding `Disable $p% of Desired Size` stage with context $disableContext [executionId=${stage.execution.id}]")

        stages << newStage(
          stage.execution,
          disableServerGroupStage.type,
          "Disable $p% of Traffic on ${source.serverGroupName}",
          disableContext,
          stage,
          SyntheticStageOwner.STAGE_AFTER
        )
      }
    })

    // only scale down if we have a source server group to scale down
    if (source && stageData.scaleDown) {
      if(stageData?.getDelayBeforeScaleDown()) {
        def waitContext = [waitTime: stageData?.getDelayBeforeScaleDown()]
        stages << newStage(
          stage.execution,
          waitStage.type,
          "Wait Before Scale Down",
          waitContext,
          stage,
          SyntheticStageOwner.STAGE_AFTER
        )
      }

      def scaleDown = baseContext + [
        allowScaleDownActive         : false,
        remainingFullSizeServerGroups: 1,
        preferLargerOverNewer        : false
      ]
      stages << newStage(
        stage.execution,
        scaleDownClusterStage.type,
        "scaleDown",
        scaleDown,
        stage,
        SyntheticStageOwner.STAGE_AFTER
      )
    }

    // Only unpin if we have a source ASG and we didn't scale it down
    if (source && !stageData.scaleDown) {
      def resizeContext = baseContext
      resizeContext.putAll([
        serverGroupName     : source.serverGroupName,
        action              : ResizeStrategy.ResizeAction.scale_to_server_group,
        source              : source,
        useNameAsLabel      : true,     // hint to deck that it should _not_ override the name
        unpinMinimumCapacity: true
      ])

      stages << newStage(
        stage.execution,
        PinServerGroupStage.TYPE,
        "Unpin ${resizeContext.serverGroupName}",
        resizeContext,
        stage,
        SyntheticStageOwner.STAGE_AFTER
      )
    }

    return stages
  }

  @Override
  List<Stage> composeOnFailureStages(Stage parent) {
    def source = null
    def stages = []

    try {
      source = lookupSourceServerGroup(parent)
    } catch (Exception e) {
      log.warn("Failed to lookup source server group during composeOnFailureStages", e)
    }

    // No source, nothing to unpin
    if (source == null) {
      return stages
    }

    def cleanupConfig = AbstractDeployStrategyStage.CleanupConfig.fromStage(parent)

    Map baseContext = [
      (cleanupConfig.location.singularType()): cleanupConfig.location.value,
      cluster                                : cleanupConfig.cluster,
      moniker                                : cleanupConfig.moniker,
      credentials                            : cleanupConfig.account,
      cloudProvider                          : cleanupConfig.cloudProvider,
    ]

    def resizeContext = baseContext
    resizeContext.putAll([
      serverGroupName     : source.serverGroupName,
      action              : ResizeStrategy.ResizeAction.scale_to_server_group,
      source              : source,
      useNameAsLabel      : true,     // hint to deck that it should _not_ override the name
      unpinMinimumCapacity: true,
      // we want to specify a new timeout explicitly here, in case the deploy itself failed because of a timeout
      stageTimeoutMs      : TimeUnit.MINUTES.toMillis(20)
    ])

    stages << newStage(
      parent.execution,
      PinServerGroupStage.TYPE,
      "Unpin ${resizeContext.serverGroupName}",
      resizeContext,
      parent,
      SyntheticStageOwner.STAGE_AFTER
    )

    return stages
  }

  ResizeStrategy.Source lookupSourceServerGroup(Stage stage) {
    ResizeStrategy.Source source = null
    StageData.Source sourceServerGroup

    Stage parentCreateServerGroupStage = stage.directAncestors()
      .find() { it.type == CreateServerGroupStage.PIPELINE_CONFIG_TYPE || it.type == CloneServerGroupStage.PIPELINE_CONFIG_TYPE }

    StageData parentStageData = parentCreateServerGroupStage.mapTo(StageData)
    sourceServerGroup = parentStageData.source
    def stageData = stage.mapTo(RollingRedBlackStageData)

    if (sourceServerGroup != null && (sourceServerGroup.serverGroupName != null || sourceServerGroup.asgName != null)) {
      source = new ResizeStrategy.Source(
        region: sourceServerGroup.region,
        serverGroupName: sourceServerGroup.serverGroupName ?: sourceServerGroup.asgName,
        credentials: stageData.credentials ?: stageData.account,
        cloudProvider: stageData.cloudProvider
      )
    }

    return source
  }

  List<Stage> getBeforeCleanupStages(Stage parentStage,
                                     RollingRedBlackStageData stageData,
                                     AbstractDeployStrategyStage.CleanupConfig cleanupConfig,
                                     String sourceServerGroupName,
                                     String deployedServerGroupName,
                                     int percentageComplete) {
    def stages = []

    if (stageData.getDelayBeforeCleanup()) {
      def waitContext = [waitTime: stageData.getDelayBeforeCleanup()]
      stages << newStage(
        parentStage.execution,
        waitStage.type,
        "wait",
        waitContext,
        parentStage,
        SyntheticStageOwner.STAGE_AFTER
      )
    }

    if (stageData.pipelineBeforeCleanup?.application && stageData.pipelineBeforeCleanup?.pipelineId) {
      def serverGroupCoordinates = [
        region         : cleanupConfig.location.value,
        account        : cleanupConfig.account,
        cloudProvider  : cleanupConfig.cloudProvider
      ]

      def pipelineContext = [
        application        : stageData.pipelineBeforeCleanup.application,
        pipelineApplication: stageData.pipelineBeforeCleanup.application,
        pipeline           : stageData.pipelineBeforeCleanup.pipelineId,
        pipelineParameters : stageData.pipelineBeforeCleanup.pipelineParameters + [
          "deployedServerGroup": serverGroupCoordinates + [
            serverGroupName: deployedServerGroupName
          ],
          "sourceServerGroup"  : serverGroupCoordinates + [
            serverGroupName: sourceServerGroupName
          ],
          "percentageComplete" : percentageComplete
        ]
      ]

      stages << newStage(
        parentStage.execution,
        pipelineStage.type,
        "Run Validation Pipeline",
        pipelineContext,
        parentStage,
        SyntheticStageOwner.STAGE_AFTER
      )
    }

    return stages
  }

  ApplicationContext applicationContext
}
