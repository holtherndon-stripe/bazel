// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.query2.cquery;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetNotFoundException;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryVisibility;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.devtools.build.skyframe.state.EnvironmentForUtilities;
import java.util.List;

/**
 * A {@link TargetAccessor} for {@link ConfiguredTarget} objects.
 *
 * <p>Incomplete; we'll implement getVisibility when needed.
 */
public class ConfiguredTargetAccessor implements TargetAccessor<ConfiguredTarget> {

  private final WalkableGraph walkableGraph;
  private final ConfiguredTargetQueryEnvironment queryEnvironment;
  private final SkyFunction.LookupEnvironment lookupEnvironment;

  public ConfiguredTargetAccessor(
      WalkableGraph walkableGraph, ConfiguredTargetQueryEnvironment queryEnvironment) {
    this.walkableGraph = walkableGraph;
    this.queryEnvironment = queryEnvironment;
    this.lookupEnvironment =
        new EnvironmentForUtilities(
            key -> {
              try {
                SkyValue value = walkableGraph.getValue(key);
                if (value != null) {
                  return value;
                }
                return walkableGraph.getException(key);
              } catch (InterruptedException e) {
                throw new IllegalStateException(
                    "Thread interrupted in the middle of looking up: " + key, e);
              }
            });
  }

  @Override
  public String getTargetKind(ConfiguredTarget target) {
    Target actualTarget = getTarget(target);
    return actualTarget.getTargetKind();
  }

  @Override
  public String getLabel(ConfiguredTarget target) {
    return target.getOriginalLabel().toString();
  }

  @Override
  public String getPackage(ConfiguredTarget target) {
    return target.getOriginalLabel().getPackageIdentifier().getPackageFragment().toString();
  }

  @Override
  public boolean isRule(ConfiguredTarget target) {
    Target actualTarget = getTarget(target);
    return actualTarget instanceof Rule;
  }

  @Override
  public boolean isTestRule(ConfiguredTarget target) {
    Target actualTarget = getTarget(target);
    return TargetUtils.isTestRule(actualTarget);
  }

  @Override
  public boolean isTestSuite(ConfiguredTarget target) {
    Target actualTarget = getTarget(target);
    return TargetUtils.isTestSuiteRule(actualTarget);
  }

  @Override
  public List<ConfiguredTarget> getPrerequisites(
      QueryExpression caller,
      ConfiguredTarget keyedConfiguredTarget,
      String attrName,
      String errorMsgPrefix)
      throws QueryException, InterruptedException {
    // Process aliases.
    ConfiguredTarget actual = keyedConfiguredTarget.getActual();

    Preconditions.checkArgument(
        isRule(actual), "%s %s is not a rule configured target", errorMsgPrefix, getLabel(actual));

    ImmutableListMultimap<Label, ConfiguredTarget> depsByLabel =
        Multimaps.index(
            queryEnvironment.getFwdDeps(ImmutableList.of(actual)),
            ConfiguredTarget::getOriginalLabel);

    Rule rule = (Rule) getTarget(actual);
    ImmutableMap<Label, ConfigMatchingProvider> configConditions = actual.getConfigConditions();
    ConfiguredAttributeMapper attributeMapper =
        ConfiguredAttributeMapper.of(
            rule,
            configConditions,
            keyedConfiguredTarget.getConfigurationChecksum(),
            /*alwaysSucceed=*/ false);
    if (!attributeMapper.has(attrName)) {
      throw new QueryException(
          caller,
          String.format(
              "%sconfigured target of type %s does not have attribute '%s'",
              errorMsgPrefix, rule.getRuleClass(), attrName),
          ConfigurableQuery.Code.ATTRIBUTE_MISSING);
    }
    ImmutableList.Builder<ConfiguredTarget> toReturn = ImmutableList.builder();
    attributeMapper.visitLabels(attrName, label -> toReturn.addAll(depsByLabel.get(label)));
    return toReturn.build();
  }

  @Override
  public List<String> getStringListAttr(ConfiguredTarget target, String attrName) {
    Target actualTarget = getTarget(target);
    return TargetUtils.getStringListAttr(actualTarget, attrName);
  }

  @Override
  public String getStringAttr(ConfiguredTarget target, String attrName) {
    Target actualTarget = getTarget(target);
    return TargetUtils.getStringAttr(actualTarget, attrName);
  }

  @Override
  public Iterable<String> getAttrAsString(ConfiguredTarget target, String attrName) {
    Target actualTarget = getTarget(target);
    return TargetUtils.getAttrAsString(actualTarget, attrName);
  }

  @Override
  public ImmutableSet<QueryVisibility<ConfiguredTarget>> getVisibility(
      QueryExpression caller, ConfiguredTarget from) throws QueryException {
    // TODO(bazel-team): implement this if needed.
    throw new QueryException(
        "visible() is not supported on configured targets",
        ConfigurableQuery.Code.VISIBLE_FUNCTION_NOT_SUPPORTED);
  }

  public Target getTarget(ConfiguredTarget configuredTarget) {
    // Dereference any aliases that might be present.
    Label label = configuredTarget.getOriginalLabel();
    try {
      return queryEnvironment.getTarget(label);
    } catch (InterruptedException e) {
      throw new IllegalStateException("Thread interrupted in the middle of getting a Target.", e);
    } catch (TargetNotFoundException e) {
      throw new IllegalStateException("Unable to get target from package in accessor.", e);
    }
  }

  SkyFunction.LookupEnvironment getLookupEnvironment() {
    return lookupEnvironment;
  }

  /** Returns the rule that generates the given output file. */
  RuleConfiguredTarget getGeneratingConfiguredTarget(ConfiguredTarget kct)
      throws InterruptedException {
    Preconditions.checkArgument(kct instanceof OutputFileConfiguredTarget);
    return (RuleConfiguredTarget)
        ((ConfiguredTargetValue)
                walkableGraph.getValue(
                    ConfiguredTargetKey.builder()
                        .setLabel(((OutputFileConfiguredTarget) kct).getGeneratingRule().getLabel())
                        .setConfigurationKey(kct.getConfigurationKey())
                        .build()))
            .getConfiguredTarget();
  }
}
