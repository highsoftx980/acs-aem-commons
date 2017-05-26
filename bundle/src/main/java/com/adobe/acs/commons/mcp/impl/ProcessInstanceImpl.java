/*
 * Copyright 2017 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.mcp.impl;

import com.adobe.acs.commons.mcp.*;
import com.adobe.acs.commons.fam.ActionManager;
import com.adobe.acs.commons.fam.ActionManagerFactory;
import com.adobe.acs.commons.fam.Failure;
import com.adobe.acs.commons.functions.CheckedConsumer;
import com.adobe.acs.commons.mcp.model.ManagedProcess;
import com.adobe.acs.commons.mcp.model.Result;
import com.adobe.acs.commons.mcp.util.DeserializeException;
import com.adobe.acs.commons.mcp.util.ValueMapSerializer;
import com.day.cq.commons.jcr.JcrUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularType;
import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ModifiableValueMapDecorator;
import org.slf4j.LoggerFactory;

/**
 * Abstraction of a Process which runs using FAM and consists of one or more
 * actions.
 */
public class ProcessInstanceImpl implements ProcessInstance {

    transient private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ProcessInstanceImpl.class);
    transient private static final String BASE_PATH = "/var/acs-commons/mcp/instances";
    transient private ControlledProcessManager manager = null;
    private final List<ActivityDefinition> actions;
    private final ProcessDefinition definition;
    private final ManagedProcess infoBean;
    private final String id;
    private final String path;
    transient private boolean completedNormally = false;
    transient private static final Random RANDOM = new Random();

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public double updateProgress() {
        double sectionWeight = 1.0 / actions.size();
        double progress = actions.stream()
                .map(action -> action.manager)
                .filter(action -> action.getAddedCount() > 0)
                .collect(Collectors.summingDouble(action
                        -> sectionWeight * (double) action.getCompletedCount() / (double) action.getAddedCount()
                ));
        infoBean.setProgress(progress);
        infoBean.setStatus(actions.stream().filter(a -> !a.manager.isComplete()).map(a -> a.name).findFirst().orElse("Please wait..."));
        infoBean.getResult().setTasksCompleted(
                actions.stream().collect(Collectors.summingInt(action -> action.manager.getCompletedCount()))
        );
        actions.stream().flatMap(a -> a.manager.getFailureList().stream()).collect(Collectors.toCollection(infoBean::getReportedErrors));

        return progress;
    }

    private static class ActivityDefinition {

        String name;
        ActionManager manager;
        transient CheckedConsumer<ActionManager> builder;
        boolean critical = false;
    }

    public ProcessInstanceImpl(ControlledProcessManager cpm, ProcessDefinition process, String description) {
        manager = cpm;
        infoBean = new ManagedProcess();
        infoBean.setStartTime(-1L);
        infoBean.setStopTime(-1L);
        this.actions = new ArrayList<>();
        this.definition = process;
        infoBean.setName(process.getName());
        infoBean.setDescription(description == null ? "No description" : description);
        infoBean.setResult(new Result());
        id = String.format("%016X", Math.abs(RANDOM.nextLong()));
        path = BASE_PATH + "/" + id;
    }

    @Override
    public String getName() {
        return definition.getName() != null
                ? (infoBean.getDescription() != null
                ? definition.getName() + ": " + infoBean.getDescription()
                : definition.getName())
                : (infoBean.getDescription() != null
                ? infoBean.getDescription()
                : "No idea");
    }

    @Override
    public void init(ResourceResolver resourceResolver, Map<String, Object> parameterMap) throws DeserializeException, RepositoryException {
        ValueMap inputs = new ModifiableValueMapDecorator(parameterMap);
        infoBean.setRequestInputs(inputs);
        definition.parseInputs(inputs);
    }

    @Override
    public ActionManagerFactory getActionManagerFactory() {
        return manager.getActionManagerFactory();
    }

    @Override
    final public ActionManager defineCriticalAction(String name, ResourceResolver rr, CheckedConsumer<ActionManager> builder) throws LoginException {
        return defineAction(name, rr, builder, true);
    }

    @Override
    final public ActionManager defineAction(String name, ResourceResolver rr, CheckedConsumer<ActionManager> builder) throws LoginException {
        return defineAction(name, rr, builder, false);
    }

    private ActionManager defineAction(String name, ResourceResolver rr, CheckedConsumer<ActionManager> builder, boolean isCritical) throws LoginException {
        ActivityDefinition activityDefinition = new ActivityDefinition();
        activityDefinition.builder = builder;
        activityDefinition.name = name;
        activityDefinition.manager = getActionManagerFactory().createTaskManager(getName() + ": " + name, rr, 1);
        activityDefinition.critical = isCritical;
        actions.add(activityDefinition);
        return activityDefinition.manager;
    }

    @Override
    final public void run(ResourceResolver rr) {
        try {
            infoBean.setRequester(rr.getUserID());
            infoBean.setStartTime(System.currentTimeMillis());
            definition.buildProcess(this, rr);
            infoBean.setIsRunning(true);
            runStep(0);
        } catch (LoginException | RepositoryException ex) {
            Failure f = new Failure();
            f.setException(ex);
            f.setNodePath(getPath());
            recordErrors(-1, Arrays.asList(f), rr);
            LOG.error("Error starting managed process " + getName(), ex);
            halt();
        }
    }

    private void runStep(int step) {
        if (step >= actions.size()) {
            completedNormally = true;
            halt();
        } else {
            updateProgress();
            updateStatus(step);
            asServiceUser(this::persistStatus);
            ActivityDefinition action = actions.get(step);
            if (action.critical) {
                action.manager.onSuccess(rr -> runStep(step + 1));
                action.manager.onFailure((failures, rr) -> {
                    asServiceUser(service->recordErrors(step, failures, service));
                    halt();
                });
            } else {
                action.manager.onFailure((failures, rr) -> {
                    asServiceUser(service->recordErrors(step, failures, service));
                });
                action.manager.onFinish(() -> runStep(step + 1));
            }
            action.manager.deferredWithResolver(rr -> action.builder.accept(action.manager));
        }
    }

    private void recordErrors(int step, List<Failure> failures, ResourceResolver rr) {
        if (failures.isEmpty()) {
            return;
        }
        infoBean.getReportedErrors().addAll(failures);
        try {
            String errFolder = getPath() + "/jcr:content/failures/step" + (step + 1);
            JcrUtil.createPath(errFolder, "nt:unstructured", rr.adaptTo(Session.class));
            if (rr.hasChanges()) {
                rr.commit();
                rr.refresh();
            }
            for (int i = 0; i < failures.size(); i++) {
                String errPath = errFolder + "/err" + i;
                Map<String,Object> values = new HashMap<>();
                ValueMapSerializer.serializeToMap(values, failures.get(i));
                ResourceUtil.getOrCreateResource(rr, errPath, values, null, false);
            }
            rr.commit();
        } catch (RepositoryException | PersistenceException ex) {
            LOG.error("Unable to record errors", ex);
        }
    }

    private long getRuntime() {
        long stop = System.currentTimeMillis();
        if (infoBean.getStopTime() > infoBean.getStartTime()) {
            stop = infoBean.getStopTime();
        }
        return stop - infoBean.getStartTime();
    }

    private void updateStatus(int step) {
        infoBean.setStatus("Step " + (step + 1) + ": " + actions.get(step).name);
    }

    private void setStatusCompleted() {
        infoBean.setStatus("Completed");
        infoBean.setProgress(1.0D);
    }

    private void setStatusAborted() {
        infoBean.setStatus("Aborted");
    }

    private void asServiceUser(CheckedConsumer<ResourceResolver> action) {
        ResourceResolver rr = null;
        try {
            rr = manager.getServiceResourceResolver();
            action.accept(rr);
            if (rr.hasChanges()) {
                rr.commit();
            }
        } catch (Exception ex) {
            LOG.error("Error while performing JCR operations", ex);
        } finally {
            if (rr != null) {
                rr.close();
            }
        }
    }

    private void persistStatus(ResourceResolver rr) throws PersistenceException {
        Map<String,Object> props = new HashMap<>();
        props.put(JcrConstants.JCR_PRIMARYTYPE, JcrConstants.NT_FOLDER);
        ResourceUtil.getOrCreateResource(rr, BASE_PATH, props, null, true);
        props.put(JcrConstants.JCR_PRIMARYTYPE, "cq:Page");
        Resource r = ResourceUtil.getOrCreateResource(rr, getPath(), props, null, true);
        ModifiableValueMap jcrContent = ResourceUtil.getOrCreateResource(rr, getPath() + "/jcr:content", ProcessInstance.RESOURCE_TYPE, null, false).adaptTo(ModifiableValueMap.class);
        jcrContent.put("jcr:primaryType","cq:PageContent");
        jcrContent.put("jcr:title", getName());
        ValueMapSerializer.serializeToMap(jcrContent, infoBean);
        rr.commit();
        rr.refresh();
    }

    @Override
    public ManagedProcess getInfo() {
        return infoBean;
    }

    @Override
    final public void halt() {
        infoBean.setStopTime(System.currentTimeMillis());
        infoBean.setIsRunning(false);
        if (completedNormally) {
            setStatusCompleted();
        } else {
            setStatusAborted();
        }
        asServiceUser(rr -> {
            persistStatus(rr);
            definition.storeReport(this, rr);
        });
        actions.stream().map(a -> a.manager).forEach(getActionManagerFactory()::purge);
    }

    public static TabularType getStaticsTableType() {
        return statsTabularType;
    }

    @Override
    public CompositeData getStatistics() {
        try {
            return new CompositeDataSupport(statsCompositeType, statsItemNames,
                    new Object[]{
                        id,
                        getName(),
                        actions.size(),
                        actions.stream().filter(a -> a.manager.isComplete()).collect(Collectors.counting()),
                        actions.stream().map(a -> a.manager.getSuccessCount()).collect(Collectors.summingInt(Integer::intValue)),
                        actions.stream().map(a -> a.manager.getErrorCount()).collect(Collectors.summingInt(Integer::intValue)),
                        getRuntime(),
                        updateProgress()
                    }
            );
        } catch (OpenDataException ex) {
            LOG.error("Error building output summary", ex);
            return null;
        }
    }

    private static String[] statsItemNames;
    private static CompositeType statsCompositeType;
    private static TabularType statsTabularType;

    static {
        try {
            statsItemNames = new String[]{"_id", "_taskName", "started", "completed", "successful", "errors", "runtime", "pct_complete"};
            statsCompositeType = new CompositeType(
                    "Statics Row",
                    "Single row of statistics",
                    statsItemNames,
                    new String[]{"ID", "Name", "Started", "Completed", "Successful", "Errors", "Runtime", "Percent complete"},
                    new OpenType[]{SimpleType.STRING, SimpleType.STRING, SimpleType.INTEGER, SimpleType.LONG, SimpleType.INTEGER, SimpleType.INTEGER, SimpleType.LONG, SimpleType.DOUBLE});
            statsTabularType = new TabularType("Statistics", "Collected statistics", statsCompositeType, new String[]{"_id"});
        } catch (OpenDataException ex) {
            LOG.error("Unable to build MBean composite types", ex);
        }
    }
}
