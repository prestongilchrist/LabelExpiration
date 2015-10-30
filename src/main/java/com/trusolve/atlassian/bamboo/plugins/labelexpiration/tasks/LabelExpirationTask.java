package com.trusolve.atlassian.bamboo.plugins.labelexpiration.tasks;
/* Copyright 2015 TruSolve, LLC

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;

import com.amazonaws.util.StringUtils;
import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.configuration.ConfigurationMap;
import com.atlassian.bamboo.deployments.execution.DeploymentTaskContext;
import com.atlassian.bamboo.labels.Label;
import com.atlassian.bamboo.labels.LabelManager;
import com.atlassian.bamboo.plan.PlanKeys;
import com.atlassian.bamboo.plan.PlanManager;
import com.atlassian.bamboo.plan.PlanResultKey;
import com.atlassian.bamboo.resultsummary.ResultsSummary;
import com.atlassian.bamboo.resultsummary.ResultsSummaryCriteria;
import com.atlassian.bamboo.resultsummary.ResultsSummaryManager;
import com.atlassian.bamboo.task.CommonTaskContext;
import com.atlassian.bamboo.task.CommonTaskType;
import com.atlassian.bamboo.task.TaskContext;
import com.atlassian.bamboo.task.TaskException;
import com.atlassian.bamboo.task.TaskResult;
import com.atlassian.bamboo.task.TaskResultBuilder;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;

public class LabelExpirationTask implements CommonTaskType
{
	private PlanManager planManager = null;
	public PlanManager getPlanManager()
	{
		return planManager;
	}

	public void setPlanManager(PlanManager planManager)
	{
		this.planManager = planManager;
	}

	private LabelManager labelManager = null;
	public LabelManager getLabelManager()
	{
		return labelManager;
	}

	public void setLabelManager(LabelManager labelManager)
	{
		this.labelManager = labelManager;
	}

	private ResultsSummaryManager resultsSummaryManager = null;
	public ResultsSummaryManager getResultsSummaryManager()
	{
		return resultsSummaryManager;
	}

	public void setResultsSummaryManager(ResultsSummaryManager resultsSummaryManager)
	{
		this.resultsSummaryManager = resultsSummaryManager;
	}

	private TransactionTemplate transactionTemplate = null;
	public TransactionTemplate getTransactionTemplate()
	{
		return transactionTemplate;
	}
	public void setTransactionTemplate(TransactionTemplate transactionTemplate)
	{
		this.transactionTemplate = transactionTemplate;
	}

	@Override
	public TaskResult execute(CommonTaskContext taskContext) throws TaskException
	{
		final TaskResultBuilder builder = TaskResultBuilder.newBuilder(taskContext);
		final BuildLogger buildLogger = taskContext.getBuildLogger();

		final ConfigurationMap config = taskContext.getConfigurationMap();

		final String groupingLabel = StringUtils.trim(config.get(LabelExpirationTaskConfigurator.LABELEXPIRATION_GROUPINGLABEL));
		final String groupingLabelDelete = StringUtils.trim(config.get(LabelExpirationTaskConfigurator.LABELEXPIRATION_GROUPINGLABELDELETE));
		final String expireLabel = StringUtils.trim(config.get(LabelExpirationTaskConfigurator.LABELEXPIRATION_EXPIRELABEL));
		final String labelsToRetainString = StringUtils.trim(config.get(LabelExpirationTaskConfigurator.LABELEXPIRATION_LABELSSTORETAIN));
		final String labelsToIgnoreString = StringUtils.trim(config.get(LabelExpirationTaskConfigurator.LABELEXPIRATION_LABELSSTOIGNORE));
		final List<String> labelsToIgnore = new ArrayList<String>();
		
		if( labelsToIgnoreString != null && labelsToIgnoreString.length() > 0 )
		{
			for( String l : labelsToIgnoreString.split(",") )
			{
				l = l.trim().toLowerCase();
				labelsToIgnore.add(l);
			}
		}
		
		final int labelsToRetain;
		try
		{
			labelsToRetain = Integer.parseInt(labelsToRetainString);
		}
		catch (Exception e)
		{
			throw new TaskException("Problem parsing label retention", e);
		}

		try
		{
			final String planKey;
			PlanResultKey planResultKey = null;
			final int adjustedLabelsToRetain;
			
			if( taskContext instanceof TaskContext )
			{
				TaskContext buildContext = (TaskContext) taskContext;
				planKey = buildContext.getBuildContext().getParentBuildContext().getPlanKey();
				planResultKey = buildContext.getBuildContext().getParentBuildContext().getPlanResultKey();
				// decrement the labelsToRetain since the currently building result will get the label, but won't be present in the build label search.
				adjustedLabelsToRetain = labelsToRetain - 1;
			}
			else if ( taskContext instanceof DeploymentTaskContext )
			{
				DeploymentTaskContext dtc = (DeploymentTaskContext) taskContext;
				planKey = dtc.getDeploymentContext().getVariableContext().getEffectiveVariables().get("planKey").getValue();
				planResultKey = PlanKeys.getPlanResultKey(dtc.getDeploymentContext().getVariableContext().getEffectiveVariables().get("buildResultKey").getValue());
				adjustedLabelsToRetain = labelsToRetain;
			}
			else
			{
				buildLogger.addErrorLogEntry("Task does not appear to be of type build or deployment");
				builder.failed();
				return builder.build();
			}
			labelManager.addLabel(groupingLabel, planResultKey, null);
			if( ! groupingLabel.equalsIgnoreCase(expireLabel) )
			{
				labelManager.addLabel(expireLabel, planResultKey, null);
			}
			
			
			
			transactionTemplate.execute(new TransactionCallback<Object>()
			{
				@Override
				public Object doInTransaction()
				{
					ResultsSummaryCriteria rsc = new ResultsSummaryCriteria(planKey);
					rsc.setMatchesLabels(new ArrayList<Label>(labelManager.getLabelsByName(Arrays.asList(new String[]{groupingLabel}))));
					int i = 0;
					for( ResultsSummary rs : resultsSummaryManager.getResultSummaries(rsc) )
					{
						i++;
						if( i <= adjustedLabelsToRetain )
						{
							continue;
						}
						final PlanResultKey prk = rs.getPlanResultKey();
						if( "true".equalsIgnoreCase(groupingLabelDelete) )
						{
							labelManager.removeLabel(groupingLabel, prk, null);
						}
						if( CollectionUtils.containsAny(labelsToIgnore, rs.getLabelNames() ) )
						{
							i--;
						}
						else
						{
							labelManager.removeLabel(expireLabel, prk, null);
						}
					}
					return null;
				}
			});
		}
		catch (Exception e)
		{
			buildLogger.addErrorLogEntry("Exception: " + e.getMessage());
			builder.failed();
		}
		return builder.build();
	}
}
