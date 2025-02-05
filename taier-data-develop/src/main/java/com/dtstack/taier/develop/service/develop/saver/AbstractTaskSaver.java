package com.dtstack.taier.develop.service.develop.saver;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dtstack.taier.common.enums.EComputeType;
import com.dtstack.taier.common.enums.EScheduleJobType;
import com.dtstack.taier.common.enums.EScheduleStatus;
import com.dtstack.taier.common.enums.ESubmitStatus;
import com.dtstack.taier.common.enums.TaskTemplateType;
import com.dtstack.taier.common.exception.ErrorCode;
import com.dtstack.taier.common.exception.RdosDefineException;
import com.dtstack.taier.common.util.SqlFormatUtil;
import com.dtstack.taier.dao.domain.Task;
import com.dtstack.taier.dao.domain.TaskTemplate;
import com.dtstack.taier.dao.domain.TenantComponent;
import com.dtstack.taier.dao.mapper.DevelopTaskMapper;
import com.dtstack.taier.develop.dto.devlop.TaskResourceParam;
import com.dtstack.taier.develop.dto.devlop.TaskVO;
import com.dtstack.taier.develop.enums.develop.FlinkVersion;
import com.dtstack.taier.develop.mapstruct.vo.TaskMapstructTransfer;
import com.dtstack.taier.develop.service.develop.ITaskSaver;
import com.dtstack.taier.develop.service.develop.impl.DevelopTaskParamService;
import com.dtstack.taier.develop.service.develop.impl.DevelopTaskService;
import com.dtstack.taier.develop.service.develop.impl.DevelopTaskTaskService;
import com.dtstack.taier.develop.service.develop.impl.DevelopTenantComponentService;
import com.dtstack.taier.develop.service.task.TaskTemplateService;
import com.dtstack.taier.pluginapi.enums.EJobType;
import com.dtstack.taier.scheduler.service.ScheduleActionService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * @Author: zhichen
 * @Date: 2022/05/29/4:55 PM
 */
@Component
public abstract class AbstractTaskSaver implements ITaskSaver {

    protected final static String SQL_NOTE_TEMPLATE =
            "-- name %s \n" +
                    "-- type %s \n" +
                    "-- author %s \n" +
                    "-- create time %s \n" +
                    "-- desc %s \n";

    public static final String DEFAULT_SCHEDULE_CONF = "{" +
            "\"selfReliance\":0, " +
            "\"min\":0," +
            "\"hour\":0," +
            "\"periodType\":\"2\"," +
            "\"beginDate\":\"2001-01-01\"," +
            "\"endDate\":\"2121-01-01\"," +
            "\"isFailRetry\":true," +
            "\"maxRetryNum\":\"3\"" +
            "}";

    Predicate<EScheduleJobType> isBatchSQL = scheduleJobType -> EJobType.SQL.getType() == scheduleJobType.getEngineJobType()
            && EComputeType.BATCH.equals(scheduleJobType.getComputeType());

    @Autowired
    public DevelopTaskService developTaskService;

    @Autowired
    public DevelopTaskMapper developTaskMapper;

    @Autowired
    public DevelopTaskParamService developTaskParamService;

    @Autowired
    private ScheduleActionService actionService;

    @Autowired
    public TaskTemplateService taskTemplateService;

    @Autowired
    private DevelopTenantComponentService developTenantComponentService;

    @Autowired
    private DevelopTaskTaskService developTaskTaskService;

    public abstract TaskResourceParam beforeProcessing(TaskResourceParam taskResourceParam);

    public abstract void afterProcessing(TaskResourceParam taskResourceParam, TaskVO taskVO);


    /**
     * 任务编辑添加入口
     *
     * @param taskResourceParam
     * @return
     */
    @Override
    public TaskVO addOrUpdate(TaskResourceParam taskResourceParam) {

        beforeProcessing(taskResourceParam);

        TaskVO taskVO = updateTaskInfo(taskResourceParam);

        afterProcessing(taskResourceParam, taskVO);

        return taskVO;
    }

    @Override
    public String processScheduleRunSqlText(Long tenantId, Integer taskType, String sqlText) {
        EScheduleJobType scheduleJobType = EScheduleJobType.getByTaskType(taskType);
        if (!isBatchSQL.test(scheduleJobType)) {
            return SqlFormatUtil.formatSql(sqlText);
        }
        TenantComponent tenantEngine = developTenantComponentService.getByTenantAndTaskType(tenantId, taskType);
        if (null == tenantEngine || StringUtils.isBlank(tenantEngine.getComponentIdentity())) {
            return SqlFormatUtil.formatSql(sqlText);
        }
        StringBuilder sqlBuild = new StringBuilder();
        sqlBuild.append("use ").append(tenantEngine.getComponentIdentity().toLowerCase()).append(";\n");
        sqlBuild.append(SqlFormatUtil.formatSql(sqlText));
        return sqlBuild.toString();
    }

    /**
     * 处理任务信息
     *
     * @param taskResourceParam
     * @return
     */
    public TaskVO updateTaskInfo(TaskResourceParam taskResourceParam) {
        TaskVO taskVO = TaskMapstructTransfer.INSTANCE.TaskResourceParamToTaskVO(taskResourceParam);
        taskVO.setModifyUserId(taskResourceParam.getUserId());
        if (StringUtils.isBlank(taskVO.getName())) {
            throw new RdosDefineException("名称不能为空", ErrorCode.INVALID_PARAMETERS);
        }
        taskVO.setGmtModified(Timestamp.valueOf(LocalDateTime.now()));

        Task task = developTaskService.getOne(Wrappers.lambdaQuery(Task.class)
                .eq(Task::getName, taskVO.getName())
                .eq(Task::getTenantId, taskVO.getTenantId()));

        if (taskVO.getId() != null && taskVO.getId() > 0) {
            //update
            if (Objects.nonNull(task)
                    && task.getName().equals(taskVO.getName())
                    && !task.getId().equals(taskVO.getId())) {
                throw new RdosDefineException(ErrorCode.NAME_ALREADY_EXIST);
            }
            developTaskParamService.checkParams(taskVO.getSqlText(), taskVO.getTaskVariables());
            updateTask(taskVO);

        } else {
            if (Objects.nonNull(task)) {
                throw new RdosDefineException(ErrorCode.NAME_ALREADY_EXIST);
            }
            addTask(taskVO);
        }
        developTaskParamService.addOrUpdateTaskParam(taskVO.getTaskVariables(), taskVO.getId());

        return taskVO;
    }

    private void updateTask(TaskVO taskVO) {
        Task specialTask = developTaskService.getOne(taskVO.getId());
        if (specialTask == null) {
            throw new RdosDefineException(ErrorCode.CAN_NOT_FIND_TASK);
        }
        Task specialTask1 = new Task();
        // 转换环境参数
        if (!Objects.equals(EScheduleJobType.WORK_FLOW.getVal(), taskVO.getTaskType())) {
            String convertParams = convertParams(FlinkVersion.getVersion(specialTask.getComponentVersion()),
                    FlinkVersion.getVersion(taskVO.getComponentVersion()),
                    taskVO.getTaskParams(), taskVO.getTaskType());
            taskVO.setTaskParams(convertParams);

            TaskMapstructTransfer.INSTANCE.taskVOTOTask(taskVO, specialTask1);
            developTaskService.updateById(specialTask1);
        } else {
            TaskMapstructTransfer.INSTANCE.taskVOTOTask(taskVO, specialTask1);
            specialTask1.setSqlText(String.valueOf(JSONObject.toJSON(taskVO.getNodeMap())));
            developTaskService.updateById(specialTask1);
        }


        if (EScheduleJobType.WORK_FLOW.getVal().equals(specialTask.getTaskType())) {
            // 判断任务依赖是否成环
            if (MapUtils.isNotEmpty(taskVO.getNodeMap())) {
                taskVO.setSqlText(String.valueOf(JSONObject.toJSON(taskVO.getNodeMap())));
                checkIsLoopByList(taskVO.getNodeMap());
            }
            for (Map.Entry<Long, List<Long>> entry : taskVO.getNodeMap().entrySet()) {
                List<TaskVO> dependencyTasks = getTaskByIds(entry.getValue());
                developTaskTaskService.addOrUpdateTaskTask(entry.getKey(), dependencyTasks);
            }


            List<Task> childrenTaskByFlowId = developTaskService.getFlowWorkSubTasks(specialTask.getId());
            if (CollectionUtils.isNotEmpty(childrenTaskByFlowId)) {
                developTaskService.updateSubTaskScheduleConf(taskVO.getId(), JSONObject.parseObject(taskVO.getScheduleConf()));
            }

            //更新父节点目录时，同步更新子节点
            if (!taskVO.getNodePid().equals(specialTask.getNodePid())) {
                developTaskService.updateSonTaskNodePidByFlowId(taskVO.getId(), taskVO.getNodePid());
            }
        }

    }


    /**
     * 判断是否成环
     *
     * @param nodeMap 任务完整依赖关系  key：节点  value 节点的所有父节点
     * @return
     */
    public void checkIsLoopByList(Map<Long, List<Long>> nodeMap) {
        if (MapUtils.isEmpty(nodeMap)) {
            return;
        }
        for (Map.Entry<Long, List<Long>> entry : nodeMap.entrySet()) {
            mapDfs(entry.getKey(), new HashSet(), nodeMap);
        }
    }

    /**
     * 图深度遍历
     *
     * @param taskId  任务ID
     * @param set     已经遍历过的节点
     * @param nodeMap 任务完整依赖关系  key：节点  value 节点的所有父节点
     */
    private void mapDfs(Long taskId, HashSet<Long> set, Map<Long, List<Long>> nodeMap) {
        HashSet<Long> node = new HashSet<>(set);
        // 判断该节点是否以及存在，如果存在，则证明成环了
        if (set.contains(taskId)) {
            Task task = developTaskMapper.selectById(taskId);
            if (Objects.nonNull(task)) {
                throw new RdosDefineException(String.format("%s任务发生依赖闭环", task.getName()));
            }
        }
        node.add(taskId);
        for (Long j : nodeMap.get(taskId)) {
            mapDfs(j, node, nodeMap);
        }
    }

    /**
     * 新增任务
     *
     * @param task 任务信息
     */
    private void addTask(TaskVO task) {
        task.setJobId(actionService.generateUniqueSign());
        task.setGmtCreate(Timestamp.valueOf(LocalDateTime.now()));
        if (StringUtils.isBlank(task.getTaskParams())) {
            TaskTemplate taskTemplate = taskTemplateService.getTaskTemplate(TaskTemplateType.TASK_PARAMS.getType(), task.getTaskType(), task.getComponentVersion());
            String content = taskTemplate == null ? "" : taskTemplate.getContent();
            task.setTaskParams(content);
        }
        task.setScheduleStatus(EScheduleStatus.NORMAL.getVal());
        task.setScheduleConf(task.getScheduleConf());
        task.setSqlText(Objects.isNull(task.getSqlText()) ? "" : task.getSqlText());
        task.setVersion(Objects.isNull(task.getVersion()) ? 0 : task.getVersion());
        task.setMainClass(Objects.isNull(task.getMainClass()) ? "" : task.getMainClass());
        task.setTaskDesc(Objects.isNull(task.getTaskDesc()) ? "" : task.getTaskDesc());
        task.setSubmitStatus(ESubmitStatus.UNSUBMIT.getStatus());
        task.setCreateUserId(task.getModifyUserId());
        task.setScheduleConf(StringUtils.isBlank(task.getScheduleConf()) ? AbstractTaskSaver.DEFAULT_SCHEDULE_CONF : task.getScheduleConf());
        developTaskService.save(task);
    }

    /**
     * 转化环境参数，不同版本之间切换需要刷新环境参数信息
     *
     * @param before       转化前的 flink 版本
     * @param after        转化后的 flink 版本
     * @param paramsBefore 环境参数
     * @return 转化后的环境参数
     */
    private String convertParams(FlinkVersion before, FlinkVersion after, String paramsBefore, Integer taskType) {
        // 版本一致不需要进行转换
        if (before.equals(after)) {
            return paramsBefore;
        }
        return taskTemplateService.getTaskTemplate(TaskTemplateType.TASK_PARAMS.getType(), taskType, after.getType()).getContent();
    }

    public List<TaskVO> getTaskByIds(List<Long> taskIdArray) {
        if (CollectionUtils.isEmpty(taskIdArray)) {
            return Collections.EMPTY_LIST;
        }
        List<Task> tasks = developTaskMapper.selectList(
                Wrappers.lambdaQuery(Task.class).in(Task::getId, taskIdArray));
        ArrayList<TaskVO> taskVOS = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(tasks)) {
            tasks.stream().forEach(x -> {
                TaskVO taskVO = new TaskVO();
                BeanUtils.copyProperties(x, taskVO);
                taskVOS.add(taskVO);
            });
        }
        return taskVOS;
    }

}
