/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.api.pojo.Page
import com.tencent.devops.common.api.util.JsonUtil
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.pojo.element.ElementAdditionalOptions
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.model.process.tables.records.TPipelineInfoRecord
import com.tencent.devops.model.process.tables.records.TPipelineModelTaskRecord
import com.tencent.devops.process.engine.common.Timeout.MAX_MINUTES
import com.tencent.devops.process.engine.control.ControlUtils
import com.tencent.devops.process.engine.dao.PipelineInfoDao
import com.tencent.devops.process.engine.dao.PipelineModelTaskDao
import com.tencent.devops.process.engine.pojo.PipelineBuildTask
import com.tencent.devops.process.engine.pojo.PipelineModelTask
import com.tencent.devops.process.engine.service.PipelinePauseExtService
import com.tencent.devops.process.engine.service.PipelineRuntimeService
import com.tencent.devops.process.engine.service.detail.TaskBuildDetailService
import com.tencent.devops.process.engine.utils.PauseRedisUtils
import com.tencent.devops.process.pojo.PipelineProjectRel
import com.tencent.devops.process.util.TaskUtils
import com.tencent.devops.process.utils.BK_CI_BUILD_FAIL_TASKNAMES
import com.tencent.devops.process.utils.BK_CI_BUILD_FAIL_TASKS
import com.tencent.devops.process.utils.KEY_PIPELINE_ID
import com.tencent.devops.process.utils.KEY_PROJECT_ID
import com.tencent.devops.store.pojo.common.KEY_VERSION
import org.jooq.DSLContext
import org.jooq.Result
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("ALL")
@Service
class PipelineTaskService @Autowired constructor(
    private val dslContext: DSLContext,
    private val redisOperation: RedisOperation,
    private val objectMapper: ObjectMapper,
    private val pipelineInfoDao: PipelineInfoDao,
    private val taskBuildDetailService: TaskBuildDetailService,
    private val pipelineModelTaskDao: PipelineModelTaskDao,
    private val buildLogPrinter: BuildLogPrinter,
    private val pipelineVariableService: BuildVariableService,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelinePauseExtService: PipelinePauseExtService
) {

    fun list(projectId: String, pipelineIds: Collection<String>): Map<String, List<PipelineModelTask>> {
        return pipelineModelTaskDao.listByPipelineIds(dslContext, projectId, pipelineIds)?.map {
            PipelineModelTask(
                projectId = it.projectId,
                pipelineId = it.pipelineId,
                stageId = it.stageId,
                containerId = it.containerId,
                taskId = it.taskId,
                taskSeq = it.taskSeq,
                taskName = it.taskName,
                atomCode = it.atomCode,
                atomVersion = it.atomVersion,
                classType = it.classType,
                taskAtom = it.taskAtom,
                taskParams = objectMapper.readValue(it.taskParams),
                additionalOptions = if (it.additionalOptions.isNullOrBlank()) {
                    null
                } else {
                    objectMapper.readValue(it.additionalOptions, ElementAdditionalOptions::class.java)
                },
                os = it.os
            )
        }?.groupBy { it.pipelineId } ?: mapOf()
    }

    /**
     * 根据插件标识，获取使用插件的流水线详情
     */
    @Suppress("UNCHECKED_CAST")
    fun listPipelinesByAtomCode(
        atomCode: String,
        projectCode: String?,
        page: Int?,
        pageSize: Int?
    ): Page<PipelineProjectRel> {
        val pageNotNull = PageUtil.getValidPage(page)
        val pageSizeNotNull = PageUtil.getValidPageSize(pageSize)

        val count = pipelineModelTaskDao.getPipelineCountByAtomCode(dslContext, atomCode, projectCode).toLong()
        val pipelineTasks =
            pipelineModelTaskDao.listByAtomCode(
                dslContext = dslContext,
                atomCode = atomCode,
                projectId = projectCode,
                page = pageNotNull,
                pageSize = pageSizeNotNull
            )

        val pipelineAtomVersionInfo = mutableMapOf<String, MutableSet<String>>()
        val pipelineIds = pipelineTasks?.map { it[KEY_PIPELINE_ID] as String }
        var pipelineNameMap: MutableMap<String, String>? = null
        if (pipelineIds != null && pipelineIds.isNotEmpty()) {
            pipelineNameMap = mutableMapOf()
            val pipelineAtoms = pipelineModelTaskDao.listByAtomCodeAndPipelineIds(
                dslContext = dslContext,
                atomCode = atomCode,
                pipelineIdList = pipelineIds
            )
            pipelineAtoms?.forEach {
                val version = it[KEY_VERSION] as? String ?: return@forEach
                val pipelineId = it[KEY_PIPELINE_ID] as String
                if (pipelineAtomVersionInfo.containsKey(pipelineId)) {
                    pipelineAtomVersionInfo[pipelineId]!!.add(version)
                } else {
                    pipelineAtomVersionInfo[pipelineId] = mutableSetOf(version)
                }
            }
            val pipelineInfoRecords =
                pipelineInfoDao.listInfoByPipelineIds(dslContext = dslContext, pipelineIds = pipelineIds.toSet())
            pipelineInfoRecords.forEach {
                pipelineNameMap[it.pipelineId] = it.pipelineName
            }
        }

        val records = pipelineTasks?.map {
            val pipelineId = it[KEY_PIPELINE_ID] as String
            PipelineProjectRel(
                pipelineId = pipelineId,
                pipelineName = pipelineNameMap?.get(pipelineId) ?: "",
                projectCode = it[KEY_PROJECT_ID] as String,
                atomVersion = pipelineAtomVersionInfo[pipelineId]?.joinToString(",") ?: ""
            )
        }
            ?: listOf<PipelineProjectRel>()

        return Page(pageNotNull, pageSizeNotNull, count, records)
    }

    fun listPipelineNumByAtomCodes(projectId: String? = null, atomCodes: List<String>): Map<String, Int> {
        val dataMap = mutableMapOf<String, Int>()
        atomCodes.forEach { atomCode ->
            val count = pipelineModelTaskDao.getPipelineCountByAtomCode(dslContext, atomCode, projectId)
            dataMap[atomCode] = count
        }
        return dataMap
    }

    fun isRetryWhenFail(taskId: String, buildId: String): Boolean {
        val taskRecord = pipelineRuntimeService.getBuildTask(buildId, taskId)
            ?: return false
        val retryCount = redisOperation.get(
            TaskUtils.getFailRetryTaskRedisKey(buildId = taskRecord.buildId, taskId = taskRecord.taskId)
        )?.toInt() ?: 0
        val isRry = ControlUtils.retryWhenFailure(taskRecord.additionalOptions, retryCount)
        if (isRry) {
            val nextCount = retryCount + 1
            redisOperation.set(
                key = TaskUtils.getFailRetryTaskRedisKey(buildId = taskRecord.buildId, taskId = taskRecord.taskId),
                value = nextCount.toString()
            )
            buildLogPrinter.addYellowLine(
                buildId = buildId,
                message = "[${taskRecord.taskName}] failed, and retry $nextCount",
                tag = taskRecord.taskId,
                jobId = taskRecord.containerId,
                executeCount = 1
            )
        }
        return isRry
    }

    fun isNeedPause(taskId: String, buildId: String, taskRecord: PipelineBuildTask): Boolean {
        val alreadyPause = redisOperation.get(PauseRedisUtils.getPauseRedisKey(buildId = buildId, taskId = taskId))
        return ControlUtils.pauseBeforeExec(taskRecord.additionalOptions, alreadyPause)
    }

    fun executePause(taskId: String, buildId: String, taskRecord: PipelineBuildTask) {
        buildLogPrinter.addYellowLine(
            buildId = buildId,
            message = "[${taskRecord.taskName}] pause, waiting ...",
            tag = taskRecord.taskId,
            jobId = taskRecord.containerId,
            executeCount = taskRecord.executeCount ?: 1
        )

        pauseBuild(task = taskRecord)

        pipelinePauseExtService.sendPauseNotify(buildId, taskRecord)
    }

    fun removeRetryCache(buildId: String, taskId: String) {
        // 清除该原子内的重试记录
        redisOperation.delete(TaskUtils.getFailRetryTaskRedisKey(buildId = buildId, taskId = taskId))
    }

    fun createFailTaskVar(buildId: String, projectId: String, pipelineId: String, taskId: String) {
        val taskRecord = pipelineRuntimeService.getBuildTask(buildId, taskId)
            ?: return
        val model = taskBuildDetailService.getBuildModel(projectId, buildId)
        val failTask = pipelineVariableService.getVariable(buildId, BK_CI_BUILD_FAIL_TASKS)
        val failTaskNames = pipelineVariableService.getVariable(buildId, BK_CI_BUILD_FAIL_TASKNAMES)
        try {
            val errorElement = findElementMsg(model, taskRecord)
            val errorElements = if (failTask.isNullOrBlank()) {
                errorElement.first
            } else {
                failTask + errorElement.first
            }

            val errorElementsName = if (failTaskNames.isNullOrBlank()) {
                errorElement.second
            } else {
                "$failTaskNames,${errorElement.second}"
            }
            val valueMap = mutableMapOf<String, Any>()
            valueMap[BK_CI_BUILD_FAIL_TASKS] = errorElements
            valueMap[BK_CI_BUILD_FAIL_TASKNAMES] = errorElementsName
            pipelineVariableService.batchUpdateVariable(
                buildId = buildId,
                projectId = projectId,
                pipelineId = pipelineId,
                variables = valueMap
            )
        } catch (ignored: Exception) {
            logger.warn("$buildId| $taskId| createFailElementVar error, msg: $ignored")
        }
    }

    fun removeFailTaskVar(buildId: String, projectId: String, pipelineId: String, taskId: String) {
        val failTaskRecord = redisOperation.get(failTaskRedisKey(buildId = buildId, taskId = taskId))
        val failTaskNameRecord = redisOperation.get(failTaskNameRedisKey(buildId = buildId, taskId = taskId))
        if (failTaskRecord.isNullOrBlank() || failTaskNameRecord.isNullOrBlank()) {
            return
        }
        try {
            val failTask = pipelineVariableService.getVariable(buildId, BK_CI_BUILD_FAIL_TASKS)
            val failTaskNames = pipelineVariableService.getVariable(buildId, BK_CI_BUILD_FAIL_TASKNAMES)
            val newFailTask = failTask!!.replace(failTaskRecord, "")
            val newFailTaskNames = failTaskNames!!.replace(failTaskNameRecord, "")
            if (newFailTask != failTask || newFailTaskNames != failTaskNames) {
                val valueMap = mutableMapOf<String, Any>()
                valueMap[BK_CI_BUILD_FAIL_TASKS] = newFailTask
                valueMap[BK_CI_BUILD_FAIL_TASKNAMES] = newFailTaskNames
                pipelineVariableService.batchUpdateVariable(
                    buildId = buildId, projectId = projectId, pipelineId = pipelineId, variables = valueMap
                )
            }
            redisOperation.delete(failTaskRedisKey(buildId = buildId, taskId = taskId))
            redisOperation.delete(failTaskNameRedisKey(buildId = buildId, taskId = taskId))
        } catch (ignored: Exception) {
            logger.warn("$buildId|$taskId|removeFailVarWhenSuccess error, msg: $ignored")
        }
    }

    private fun failTaskRedisKey(buildId: String, taskId: String): String {
        return "devops:failTask:redis:key:$buildId:$taskId"
    }

    private fun failTaskNameRedisKey(buildId: String, taskId: String): String {
        return "devops:failTaskName:redis:key:$buildId:$taskId"
    }

    private fun findElementMsg(
        model: Model?,
        taskRecord: PipelineBuildTask
    ): Pair<String, String> {
        val containerName = findContainerName(model, taskRecord)
        val failTask = "[${taskRecord.stageId}][$containerName]${taskRecord.taskName} \n"
        val failTaskName = taskRecord.taskName

        redisOperation.set(
            key = failTaskRedisKey(buildId = taskRecord.buildId, taskId = taskRecord.taskId),
            value = failTask,
            expiredInSecond = expiredInSecond,
            expired = true
        )
        redisOperation.set(
            key = failTaskNameRedisKey(buildId = taskRecord.buildId, taskId = taskRecord.taskId),
            value = failTaskName,
            expiredInSecond = expiredInSecond,
            expired = true
        )
        return Pair(failTask, failTaskName)
    }

    @Suppress("ALL")
    private fun findContainerName(model: Model?, taskRecord: PipelineBuildTask): String {
        model?.stages?.forEach next@{ stage ->
            if (stage.id != taskRecord.stageId) {
                return@next
            }
            stage.containers.forEach { container ->
                if (container.id == taskRecord.containerId) {
                    return container.name
                }
            }
        }
        return ""
    }

    fun pauseBuild(task: PipelineBuildTask) {
        logger.info("ENGINE|${task.buildId}|PAUSE_BUILD|${task.stageId}|j(${task.containerId})|task=${task.taskId}")
        // 修改任务状态位暂停
        pipelineRuntimeService.updateTaskStatus(task = task, userId = task.starter, buildStatus = BuildStatus.PAUSE)

        taskBuildDetailService.taskPause(
            projectId = task.projectId,
            buildId = task.buildId,
            stageId = task.stageId,
            containerId = task.containerId,
            taskId = task.taskId,
            buildStatus = BuildStatus.PAUSE
        )

        redisOperation.set(
            key = PauseRedisUtils.getPauseRedisKey(buildId = task.buildId, taskId = task.taskId),
            value = "true",
            expiredInSecond = MAX_MINUTES.toLong()
        )
    }

    /**
     * 更新ModelTask表插件版本
     */
    fun asyncUpdateTaskAtomVersion(): Boolean {
        Executors.newFixedThreadPool(1).submit {
            logger.info("begin asyncUpdateTaskAtomVersion!!")
            var offset = 0
            do {
                // 查询流水线记录
                val pipelineInfoRecords = pipelineInfoDao.listPipelineInfoByProject(
                    dslContext = dslContext,
                    offset = offset,
                    limit = DEFAULT_PAGE_SIZE
                )
                // 更新流水线任务表的插件版本
                updatePipelineTaskAtomVersion(pipelineInfoRecords)
                offset += DEFAULT_PAGE_SIZE
            } while (pipelineInfoRecords?.size == DEFAULT_PAGE_SIZE)
            logger.info("end asyncUpdateTaskAtomVersion!!")
        }
        return true
    }

    private fun updatePipelineTaskAtomVersion(pipelineInfoRecords: Result<TPipelineInfoRecord>?) {
        if (pipelineInfoRecords?.isNotEmpty == true) {
            pipelineInfoRecords.forEach { pipelineInfoRecord ->
                val modelTasks = pipelineModelTaskDao.getModelTasks(
                    dslContext = dslContext,
                    pipelineId = pipelineInfoRecord.pipelineId,
                    isAtomVersionNull = true
                )
                modelTasks?.forEach { modelTask ->
                    updateModelTaskVersion(modelTask, pipelineInfoRecord)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateModelTaskVersion(
        modelTask: TPipelineModelTaskRecord,
        pipelineInfoRecord: TPipelineInfoRecord
    ) {
        val pipelineId = modelTask.pipelineId
        val taskParamsStr = modelTask.taskParams
        val taskParams = if (!taskParamsStr.isNullOrBlank()) JsonUtil.getObjectMapper()
            .readValue(taskParamsStr, Map::class.java) as Map<String, Any?> else mapOf()
        val atomVersion = taskParams[KEY_VERSION]?.toString()
        try {
            pipelineModelTaskDao.updateTaskAtomVersion(
                dslContext = dslContext,
                atomVersion = atomVersion ?: "",
                createTime = pipelineInfoRecord.createTime,
                updateTime = pipelineInfoRecord.updateTime,
                projectId = modelTask.projectId,
                pipelineId = pipelineId,
                stageId = modelTask.stageId,
                containerId = modelTask.containerId,
                taskId = modelTask.taskId
            )
        } catch (ignored: Exception) {
            val taskName = modelTask.taskName
            logger.warn("update pipelineId:$pipelineId,taskName:$taskName version fail:", ignored)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineTaskService::class.java)
        private val expiredInSecond = TimeUnit.DAYS.toMinutes(7L)
        private const val DEFAULT_PAGE_SIZE = 50
    }
}
