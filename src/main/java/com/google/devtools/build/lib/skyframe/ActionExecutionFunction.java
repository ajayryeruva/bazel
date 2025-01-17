// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCacheChecker.Token;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionLookupValue.ActionLookupKey;
import com.google.devtools.build.lib.actions.AlreadyReportedActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.MissingInputFileException;
import com.google.devtools.build.lib.actions.NotifyOnActionCacheHit;
import com.google.devtools.build.lib.actions.PackageRootResolver;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.rules.cpp.IncludeScannable;
import com.google.devtools.build.lib.skyframe.InputArtifactData.MutableInputArtifactData;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException2;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * A {@link SkyFunction} that creates {@link ActionExecutionValue}s. There are four points where
 * this function can abort due to missing values in the graph:
 * <ol>
 *   <li>For actions that discover inputs, if missing metadata needed to resolve an artifact from a
 *   string input in the action cache.</li>
 *   <li>If missing metadata for artifacts in inputs (including the artifacts above).</li>
 *   <li>For actions that discover inputs, if missing metadata for inputs discovered prior to
 *   execution.</li>
 *   <li>For actions that discover inputs, but do so during execution, if missing metadata for
 *   inputs discovered during execution.</li>
 * </ol>
 */
public class ActionExecutionFunction implements SkyFunction, CompletionReceiver {
  private final SkyframeActionExecutor skyframeActionExecutor;
  private final AtomicReference<TimestampGranularityMonitor> tsgm;
  private ConcurrentMap<Action, ContinuationState> stateMap;

  public ActionExecutionFunction(SkyframeActionExecutor skyframeActionExecutor,
      AtomicReference<TimestampGranularityMonitor> tsgm) {
    this.skyframeActionExecutor = skyframeActionExecutor;
    this.tsgm = tsgm;
    stateMap = Maps.newConcurrentMap();
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws ActionExecutionFunctionException,
      InterruptedException {
    ActionLookupData actionLookupData = (ActionLookupData) skyKey.argument();
    ActionLookupValue actionLookupValue =
        (ActionLookupValue) env.getValue(actionLookupData.getActionLookupKey());
    int actionIndex = actionLookupData.getActionIndex();
    Action action = actionLookupValue.getAction(actionIndex);
    skyframeActionExecutor.noteActionEvaluationStarted(actionLookupData, action);
    // TODO(bazel-team): Non-volatile NotifyOnActionCacheHit actions perform worse in Skyframe than
    // legacy when they are not at the top of the action graph. In legacy, they are stored
    // separately, so notifying non-dirty actions is cheap. In Skyframe, they depend on the
    // BUILD_ID, forcing invalidation of upward transitive closure on each build.
    if ((action.isVolatile() && !(action instanceof SkyframeAwareAction))
        || action instanceof NotifyOnActionCacheHit) {
      // Volatile build actions may need to execute even if none of their known inputs have changed.
      // Depending on the buildID ensure that these actions have a chance to execute.
      PrecomputedValue.BUILD_ID.get(env);
    }

    // Look up the parts of the environment that influence the action.
    Map<SkyKey, SkyValue> clientEnvLookup =
        env.getValues(
            Iterables.transform(
                action.getClientEnvironmentVariables(), ClientEnvironmentFunction::key));
    if (env.valuesMissing()) {
      return null;
    }
    Map<String, String> clientEnv = new HashMap<>();
    for (Map.Entry<SkyKey, SkyValue> entry : clientEnvLookup.entrySet()) {
      ClientEnvironmentValue envValue = (ClientEnvironmentValue) entry.getValue();
      if (envValue.getValue() != null) {
        clientEnv.put((String) entry.getKey().argument(), envValue.getValue());
      }
    }

    // For restarts of this ActionExecutionFunction we use a ContinuationState variable, below, to
    // avoid redoing work. However, if two actions are shared and the first one executes, when the
    // second one goes to execute, we should detect that and short-circuit, even without taking
    // ContinuationState into account.
    boolean sharedActionAlreadyRan = skyframeActionExecutor.probeActionExecution(action);
    ContinuationState state;
    if (action.discoversInputs()) {
      state = getState(action);
    } else {
      // Because this is a new state, all conditionals below about whether state has already done
      // something will return false, and so we will execute all necessary steps.
      state = new ContinuationState();
    }
    if (!state.hasCollectedInputs()) {
      state.allInputs = collectInputs(action, env);
      if (state.allInputs == null) {
        // Missing deps.
        return null;
      }
    } else if (state.allInputs.keysRequested != null) {
      // Preserve the invariant that we ask for the same deps each build.
      env.getValues(state.allInputs.keysRequested);
      Preconditions.checkState(!env.valuesMissing(), "%s %s", action, state);
    }
    Pair<MutableInputArtifactData, Map<Artifact, Collection<Artifact>>> checkedInputs = null;
    try {
      // Declare deps on known inputs to action. We do this unconditionally to maintain our
      // invariant of asking for the same deps each build.
      Map<SkyKey, ValueOrException2<MissingInputFileException, ActionExecutionException>> inputDeps
          = env.getValuesOrThrow(toKeys(state.allInputs.getAllInputs(),
              action.discoversInputs() ? action.getMandatoryInputs() : null),
          MissingInputFileException.class, ActionExecutionException.class);

      if (!sharedActionAlreadyRan && !state.hasArtifactData()) {
        // Do we actually need to find our metadata?
        checkedInputs = checkInputs(env, action, inputDeps);
      }
    } catch (ActionExecutionException e) {
      // Remove action from state map in case it's there (won't be unless it discovers inputs).
      stateMap.remove(action);
      throw new ActionExecutionFunctionException(e);
    }

    if (env.valuesMissing()) {
      // There was missing artifact metadata in the graph. Wait for it to be present.
      // We must check this and return here before attempting to establish any Skyframe dependencies
      // of the action; see establishSkyframeDependencies why.
      return null;
    }

    try {
      establishSkyframeDependencies(env, action);
    } catch (ActionExecutionException e) {
      throw new ActionExecutionFunctionException(e);
    }
    if (env.valuesMissing()) {
      return null;
    }

    if (checkedInputs != null) {
      Preconditions.checkState(!state.hasArtifactData(), "%s %s", state, action);
      state.inputArtifactData = checkedInputs.first;
      state.expandedArtifacts = checkedInputs.second;
      if (skyframeActionExecutor.usesActionFileSystem()) {
        Iterable<Artifact> optionalInputs;
        if (action.discoversInputs()) {
          if (action instanceof IncludeScannable) {
            // This is a performance optimization to minimize nested set traversals for cpp
            // compilation. CppCompileAction.getAllowedDerivedInputs iterates over mandatory inputs,
            // prunable inputs, declared include srcs, transitive compilation prerequisites and
            // transitive modules.
            //
            // The only one of those sets known to be needed is the declared include srcs.
            optionalInputs = ((IncludeScannable) action).getDeclaredIncludeSrcs();
          } else {
            // This might be reachable by LtoBackendAction and ExtraAction.
            optionalInputs = action.getAllowedDerivedInputs();
          }
        } else {
          optionalInputs = ImmutableList.of();
        }
        state.actionFileSystem =
            new ActionFileSystem(
                skyframeActionExecutor.getExecRoot(),
                skyframeActionExecutor.getSourceRoots(),
                checkedInputs.first,
                optionalInputs,
                action.getOutputs());
      }
    }

    ActionExecutionValue result;
    try {
      result =
          checkCacheAndExecuteIfNeeded(
              action, state, env, clientEnv, actionLookupData, sharedActionAlreadyRan);
    } catch (ActionExecutionException e) {
      // Remove action from state map in case it's there (won't be unless it discovers inputs).
      stateMap.remove(action);
      // In this case we do not report the error to the action reporter because we have already
      // done it in SkyframeExecutor.reportErrorIfNotAbortingMode() method. That method
      // prints the error in the top-level reporter and also dumps the recorded StdErr for the
      // action. Label can be null in the case of, e.g., the SystemActionOwner (for build-info.txt).
      throw new ActionExecutionFunctionException(new AlreadyReportedActionExecutionException(e));
    }

    if (env.valuesMissing()) {
      Preconditions.checkState(stateMap.containsKey(action), action);
      return null;
    }

    // Remove action from state map in case it's there (won't be unless it discovers inputs).
    stateMap.remove(action);
    actionLookupValue.actionEvaluated(actionIndex, action);
    return result;
  }

  /**
   * An action's inputs needed for execution. May not just be the result of Action#getInputs(). If
   * the action cache's view of this action contains additional inputs, it will request metadata for
   * them, so we consider those inputs as dependencies of this action as well. Returns null if some
   * dependencies were missing and this ActionExecutionFunction needs to restart.
   *
   * @throws ActionExecutionFunctionException
   */
  @Nullable
  private AllInputs collectInputs(Action action, Environment env) throws InterruptedException {
    Iterable<Artifact> allKnownInputs = Iterables.concat(
        action.getInputs(), action.getRunfilesSupplier().getArtifacts());
    if (action.inputsDiscovered()) {
      return new AllInputs(allKnownInputs);
    }

    Preconditions.checkState(action.discoversInputs(), action);
    PackageRootResolverWithEnvironment resolver = new PackageRootResolverWithEnvironment(env);
    Iterable<Artifact> actionCacheInputs =
        skyframeActionExecutor.getActionCachedInputs(action, resolver);
    if (actionCacheInputs == null) {
      Preconditions.checkState(env.valuesMissing(), action);
      return null;
    }
    return new AllInputs(allKnownInputs, actionCacheInputs, resolver.keysRequested);
  }

  private static class AllInputs {
    final Iterable<Artifact> defaultInputs;
    @Nullable
    final Iterable<Artifact> actionCacheInputs;
    @Nullable
    final List<SkyKey> keysRequested;

    AllInputs(Iterable<Artifact> defaultInputs) {
      this.defaultInputs = Preconditions.checkNotNull(defaultInputs);
      this.actionCacheInputs = null;
      this.keysRequested = null;
    }

    AllInputs(Iterable<Artifact> defaultInputs, Iterable<Artifact> actionCacheInputs,
        List<SkyKey> keysRequested) {
      this.defaultInputs = Preconditions.checkNotNull(defaultInputs);
      this.actionCacheInputs = Preconditions.checkNotNull(actionCacheInputs);
      this.keysRequested = keysRequested;
    }

    Iterable<Artifact> getAllInputs() {
      return actionCacheInputs == null
          ? defaultInputs
          : Iterables.concat(defaultInputs, actionCacheInputs);
    }
  }

  /**
   * Skyframe implementation of {@link PackageRootResolver}. Should be used only from SkyFunctions,
   * because it uses SkyFunction.Environment for evaluation of ContainingPackageLookupValue.
   */
  private static class PackageRootResolverWithEnvironment implements PackageRootResolver {
    final List<SkyKey> keysRequested = new ArrayList<>();
    private final Environment env;

    public PackageRootResolverWithEnvironment(Environment env) {
      this.env = env;
    }

    @Override
    public Map<PathFragment, Root> findPackageRootsForFiles(Iterable<PathFragment> execPaths)
        throws InterruptedException {
      Preconditions.checkState(keysRequested.isEmpty(),
          "resolver should only be called once: %s %s", keysRequested, execPaths);
      // Create SkyKeys list based on execPaths.
      Map<PathFragment, SkyKey> depKeys = new HashMap<>();
      for (PathFragment path : execPaths) {
        PathFragment parent = Preconditions.checkNotNull(
            path.getParentDirectory(), "Must pass in files, not root directory");
        Preconditions.checkArgument(!parent.isAbsolute(), path);
        try {
          SkyKey depKey =
              ContainingPackageLookupValue.key(PackageIdentifier.discoverFromExecPath(path, true));
          depKeys.put(path, depKey);
          keysRequested.add(depKey);
        } catch (LabelSyntaxException e) {
          // This code is only used to do action cache checks. If one of the file names we got from
          // the action cache is corrupted, or if the action cache is from a different Bazel
          // binary, then the path may not be valid for this Bazel binary, and trigger this
          // exception. In that case, it's acceptable for us to ignore the exception - we'll get an
          // action cache miss and re-execute the action, which is what we should do.
          continue;
        }
      }

      Map<SkyKey, SkyValue> values = env.getValues(depKeys.values());
      if (env.valuesMissing()) {
        return null;
      }

      Map<PathFragment, Root> result = new HashMap<>();
      for (PathFragment path : execPaths) {
        if (!depKeys.containsKey(path)) {
          continue;
        }
        ContainingPackageLookupValue value =
            (ContainingPackageLookupValue) values.get(depKeys.get(path));
        if (value.hasContainingPackage()) {
          // We have found corresponding root for current execPath.
          result.put(
              path,
              SkyframeExecutor.maybeTransformRootForRepository(
                  value.getContainingPackageRoot(),
                  value.getContainingPackageName().getRepository()));
        } else {
          // We haven't found corresponding root for current execPath.
          result.put(path, null);
        }
      }
      return result;
    }
  }

  private ActionExecutionValue checkCacheAndExecuteIfNeeded(
      Action action,
      ContinuationState state,
      Environment env,
      Map<String, String> clientEnv,
      ActionLookupData actionLookupData,
      boolean sharedActionAlreadyRan)
      throws ActionExecutionException, InterruptedException {
    // If this is a shared action and the other action is the one that executed, we must use that
    // other action's value, provided here, since it is populated with metadata for the outputs.
    if (sharedActionAlreadyRan) {
      return skyframeActionExecutor.executeAction(
          env.getListener(),
          action,
          /* metadataHandler= */ null,
          /* actionStartTime= */ -1,
          /* actionExecutionContext= */ null,
          actionLookupData,
          /* inputDiscoveryRan= */ false);
    }
    // This may be recreated if we discover inputs.
    ActionMetadataHandler metadataHandler = new ActionMetadataHandler(state.inputArtifactData,
        action.getOutputs(), tsgm.get());
    long actionStartTime = BlazeClock.nanoTime();
    // We only need to check the action cache if we haven't done it on a previous run.
    if (!state.hasCheckedActionCache()) {
      state.token =
          skyframeActionExecutor.checkActionCache(
              env.getListener(),
              action,
              metadataHandler,
              actionStartTime,
              state.allInputs.actionCacheInputs,
              clientEnv);
    }

    if (state.token == null) {
      // We got a hit from the action cache -- no need to execute.
      Preconditions.checkState(
          !(action instanceof SkyframeAwareAction),
          "Error, we're not re-executing a "
              + "SkyframeAwareAction which should be re-executed unconditionally. Action: %s",
          action);
      return new ActionExecutionValue(
          metadataHandler.getOutputArtifactData(),
          metadataHandler.getOutputTreeArtifactData(),
          metadataHandler.getAdditionalOutputData(),
          /*outputSymlinks=*/ null);
    }

    // Delete the metadataHandler's cache of the action's outputs, since they are being deleted.
    metadataHandler.discardOutputMetadata();

    // This may be recreated if we discover inputs.
    // TODO(shahan): this isn't used when using ActionFileSystem so we can avoid creating some
    // unused objects.
    PerActionFileCache perActionFileCache =
        new PerActionFileCache(
            state.inputArtifactData, /*missingArtifactsAllowed=*/ action.discoversInputs());
    if (action.discoversInputs()) {
      if (state.discoveredInputs == null) {
        try {
          state.updateFileSystemContext(env, metadataHandler);
          state.discoveredInputs =
              skyframeActionExecutor.discoverInputs(
                  action, perActionFileCache, metadataHandler, env, state.actionFileSystem);
          Preconditions.checkState(state.discoveredInputs != null,
              "discoverInputs() returned null on action %s", action);
        } catch (MissingDepException e) {
          Preconditions.checkState(env.valuesMissing(), action);
          return null;
        }
      }
      addDiscoveredInputs(
          state.inputArtifactData, state.expandedArtifacts, state.discoveredInputs, env);
      if (env.valuesMissing()) {
        return null;
      }
      perActionFileCache =
          new PerActionFileCache(state.inputArtifactData, /*missingArtifactsAllowed=*/ false);

      // Stage 1 finished, let's do stage 2. The stage 1 of input discovery will have added some
      // files with addDiscoveredInputs() and then have waited for those files to be available
      // by returning null if env.valuesMissing() returned true. So stage 2 can now access those
      // inputs to discover even more inputs and then potentially also wait for those to be
      // available.
      if (state.discoveredInputsStage2 == null) {
        state.discoveredInputsStage2 = action.discoverInputsStage2(env);
      }
      if (state.discoveredInputsStage2 != null) {
        addDiscoveredInputs(
            state.inputArtifactData, state.expandedArtifacts, state.discoveredInputsStage2, env);
        if (env.valuesMissing()) {
          return null;
        }
        perActionFileCache =
            new PerActionFileCache(state.inputArtifactData, /*missingArtifactsAllowed=*/ false);
      }
      metadataHandler =
          new ActionMetadataHandler(state.inputArtifactData, action.getOutputs(), tsgm.get());
      // Set the MetadataHandler to accept output information.
      metadataHandler.discardOutputMetadata();
    }

    ImmutableMap.Builder<PathFragment, ImmutableList<FilesetOutputSymlink>> filesetMappings =
        ImmutableMap.builder();
    for (Artifact actionInput : action.getInputs()) {
      if (!actionInput.isFileset()) {
        continue;
      }

      ActionLookupKey filesetActionLookupKey = (ActionLookupKey) actionInput.getArtifactOwner();
      // Index 0 for the Fileset ConfiguredTarget indicates the SkyframeFilesetManifestAction where
      // we compute the fileset's outputSymlinks.
      SkyKey filesetActionKey = ActionExecutionValue.key(filesetActionLookupKey, 0);
      ActionExecutionValue filesetValue = (ActionExecutionValue) env.getValue(filesetActionKey);
      if (filesetValue == null) {
        // At this point skyframe does not guarantee that the filesetValue will be ready, since
        // the current action does not directly depend on the outputs of the
        // SkyframeFilesetManifestAction whose ActionExecutionValue (filesetValue) is needed here.
        // TODO(kush): Get rid of this hack by making the outputSymlinks available in the Fileset
        // artifact, which this action depends on, so its value will be guaranteed to be present.
        return null;
      }
      filesetMappings.put(actionInput.getExecPath(), filesetValue.getOutputSymlinks());
    }

    state.updateFileSystemContext(env, metadataHandler);
    try (ActionExecutionContext actionExecutionContext =
        skyframeActionExecutor.getContext(
            perActionFileCache,
            metadataHandler,
            Collections.unmodifiableMap(state.expandedArtifacts),
            filesetMappings.build(),
            state.actionFileSystem)) {
      if (!state.hasExecutedAction()) {
        state.value =
            skyframeActionExecutor.executeAction(
                env.getListener(),
                action,
                metadataHandler,
                actionStartTime,
                actionExecutionContext,
                actionLookupData,
                /* inputDiscoveryRan= */ true);
      }
    } catch (IOException e) {
      throw new ActionExecutionException(
          "Failed to close action output", e, action, /*catastrophe=*/ false);
    }

    if (action.discoversInputs()) {
      Iterable<Artifact> newInputs = filterKnownInputs(action.getInputs(), state.inputArtifactData);
      Map<SkyKey, SkyValue> metadataFoundDuringActionExecution =
          env.getValues(toKeys(newInputs, action.getMandatoryInputs()));
      state.discoveredInputs = newInputs;
      if (env.valuesMissing()) {
        return null;
      }
      if (!Iterables.isEmpty(newInputs)) {
        // We are in the interesting case of an action that discovered its inputs during
        // execution, and found some new ones, but the new ones were already present in the graph.
        // We must therefore cache the metadata for those new ones.
        for (Map.Entry<SkyKey, SkyValue> entry : metadataFoundDuringActionExecution.entrySet()) {
          state.inputArtifactData.put(
              ArtifactSkyKey.artifact(entry.getKey()), (FileArtifactValue) entry.getValue());
        }
        // TODO(ulfjack): This causes information loss about omitted and injected outputs. Also see
        // the documentation on MetadataHandler.artifactOmitted. This works by accident because
        // markOmitted is only called for remote execution, and this code only gets executed for
        // local execution.
        metadataHandler =
            new ActionMetadataHandler(state.inputArtifactData, action.getOutputs(), tsgm.get());
      }
    }
    Preconditions.checkState(!env.valuesMissing(), action);
    skyframeActionExecutor.afterExecution(
        action, metadataHandler, state.token, clientEnv, actionLookupData);
    return state.value;
  }

  private static final Function<Artifact, SkyKey> TO_NONMANDATORY_SKYKEY =
      new Function<Artifact, SkyKey>() {
        @Nullable
        @Override
        public SkyKey apply(@Nullable Artifact artifact) {
          return ArtifactSkyKey.key(artifact, /*isMandatory=*/ false);
        }
      };

  private static Iterable<SkyKey> newlyDiscoveredInputsToSkyKeys(
      Iterable<Artifact> discoveredInputs, MutableInputArtifactData inputArtifactData) {
    return Iterables.transform(
        filterKnownInputs(discoveredInputs, inputArtifactData), TO_NONMANDATORY_SKYKEY);
  }

  private static void addDiscoveredInputs(
      MutableInputArtifactData inputData,
      Map<Artifact, Collection<Artifact>> expandedArtifacts,
      Iterable<Artifact> discoveredInputs,
      Environment env)
      throws InterruptedException {
    // We do not do a getValuesOrThrow() call for the following reasons:
    // 1. No exceptions can be thrown for non-mandatory inputs;
    // 2. Any derived inputs must be in the transitive closure of this action's inputs. Therefore,
    // if there was an error building one of them, then that exception would have percolated up to
    // this action already, through one of its declared inputs, and we would not have reached input
    // discovery.
    // Therefore there is no need to catch and rethrow exceptions as there is with #checkInputs.
    Map<SkyKey, SkyValue> nonMandatoryDiscovered =
        env.getValues(newlyDiscoveredInputsToSkyKeys(discoveredInputs, inputData));
    if (!env.valuesMissing()) {
      for (Map.Entry<SkyKey, SkyValue> entry : nonMandatoryDiscovered.entrySet()) {
        Artifact input = ArtifactSkyKey.artifact(entry.getKey());
        if (entry.getValue() instanceof TreeArtifactValue) {
          TreeArtifactValue treeValue = (TreeArtifactValue) entry.getValue();
          expandedArtifacts.put(input, ImmutableSet.<Artifact>copyOf(treeValue.getChildren()));
          for (Map.Entry<Artifact.TreeFileArtifact, FileArtifactValue> child :
              treeValue.getChildValues().entrySet()) {
            inputData.put(child.getKey(), child.getValue());
          }
          inputData.put(input, treeValue.getSelfData());
        } else {
          inputData.put(input, (FileArtifactValue) entry.getValue());
        }
      }
    }
  }

  private static void establishSkyframeDependencies(Environment env, Action action)
      throws ActionExecutionException, InterruptedException {
    // Before we may safely establish Skyframe dependencies, we must build all action inputs by
    // requesting their ArtifactValues.
    // This is very important to do, because the establishSkyframeDependencies method may request
    // FileValues for input files of this action (directly requesting them, or requesting some other
    // SkyValue whose builder requests FileValues), which may not yet exist if their generating
    // actions have not yet run.
    // See SkyframeAwareActionTest.testRaceConditionBetweenInputAcquisitionAndSkyframeDeps
    Preconditions.checkState(!env.valuesMissing(), action);

    if (action instanceof SkyframeAwareAction) {
      // Skyframe-aware actions should be executed unconditionally, i.e. bypass action cache
      // checking. See documentation of SkyframeAwareAction.
      Preconditions.checkState(action.executeUnconditionally(), action);

      try {
        ((SkyframeAwareAction) action).establishSkyframeDependencies(env);
      } catch (SkyframeAwareAction.ExceptionBase e) {
        throw new ActionExecutionException(e, action, false);
      }
    }
  }

  private static Iterable<SkyKey> toKeys(Iterable<Artifact> inputs,
      Iterable<Artifact> mandatoryInputs) {
    if (mandatoryInputs == null) {
      // This is a non inputs-discovering action, so no need to distinguish mandatory from regular
      // inputs.
      return Iterables.transform(
          inputs,
          new Function<Artifact, SkyKey>() {
            @Override
            public SkyKey apply(Artifact artifact) {
              return ArtifactSkyKey.key(artifact, true);
            }
          });
    } else {
      Collection<SkyKey> discoveredArtifacts = new HashSet<>();
      Set<Artifact> mandatory = Sets.newHashSet(mandatoryInputs);
      for (Artifact artifact : inputs) {
        discoveredArtifacts.add(ArtifactSkyKey.key(artifact, mandatory.contains(artifact)));
      }
      return discoveredArtifacts;
    }
  }

  /**
   * Declare dependency on all known inputs of action. Throws exception if any are known to be
   * missing. Some inputs may not yet be in the graph, in which case the builder should abort.
   */
  private Pair<MutableInputArtifactData, Map<Artifact, Collection<Artifact>>> checkInputs(
      Environment env,
      Action action,
      Map<SkyKey, ValueOrException2<MissingInputFileException, ActionExecutionException>> inputDeps)
      throws ActionExecutionException {
    int missingCount = 0;
    int actionFailures = 0;
    // Only populate input data if we have the input values, otherwise they'll just go unused.
    // We still want to loop through the inputs to collect missing deps errors. During the
    // evaluator "error bubbling", we may get one last chance at reporting errors even though
    // some deps are still missing.
    boolean populateInputData = !env.valuesMissing();
    NestedSetBuilder<Cause> rootCauses = NestedSetBuilder.stableOrder();
    MutableInputArtifactData inputArtifactData =
        new MutableInputArtifactData(populateInputData ? inputDeps.size() : 0);
    Map<Artifact, Collection<Artifact>> expandedArtifacts =
        new HashMap<>(populateInputData ? 128 : 0);

    ActionExecutionException firstActionExecutionException = null;
    for (Map.Entry<SkyKey, ValueOrException2<MissingInputFileException,
        ActionExecutionException>> depsEntry : inputDeps.entrySet()) {
      Artifact input = ArtifactSkyKey.artifact(depsEntry.getKey());
      try {
        SkyValue value = depsEntry.getValue().get();
        if (populateInputData) {
          if (value instanceof AggregatingArtifactValue) {
            AggregatingArtifactValue aggregatingValue = (AggregatingArtifactValue) value;
            for (Pair<Artifact, FileArtifactValue> entry : aggregatingValue.getInputs()) {
              inputArtifactData.put(entry.first, entry.second);
            }
            // We have to cache the "digest" of the aggregating value itself,
            // because the action cache checker may want it.
            inputArtifactData.put(input, aggregatingValue.getSelfData());
            ImmutableList.Builder<Artifact> expansionBuilder = ImmutableList.builder();
            for (Pair<Artifact, FileArtifactValue> pair : aggregatingValue.getInputs()) {
              expansionBuilder.add(pair.first);
            }
            expandedArtifacts.put(input, expansionBuilder.build());
          } else if (value instanceof TreeArtifactValue) {
            TreeArtifactValue treeValue = (TreeArtifactValue) value;
            expandedArtifacts.put(input, ImmutableSet.<Artifact>copyOf(treeValue.getChildren()));
            for (Map.Entry<Artifact.TreeFileArtifact, FileArtifactValue> child :
                treeValue.getChildValues().entrySet()) {
              inputArtifactData.put(child.getKey(), child.getValue());
            }

            // Again, we cache the "digest" of the value for cache checking.
            inputArtifactData.put(input, treeValue.getSelfData());
          } else {
            Preconditions.checkState(value instanceof FileArtifactValue, depsEntry);
            inputArtifactData.put(input, (FileArtifactValue) value);
          }
        }
      } catch (MissingInputFileException e) {
        missingCount++;
        if (input.getOwner() != null) {
          rootCauses.add(new LabelCause(input.getOwner()));
        }
      } catch (ActionExecutionException e) {
        actionFailures++;
        // Prefer a catastrophic exception as the one we propagate.
        if (firstActionExecutionException == null
            || !firstActionExecutionException.isCatastrophe() && e.isCatastrophe()) {
          firstActionExecutionException = e;
        }
        rootCauses.addTransitive(e.getRootCauses());
      }
    }
    // We need to rethrow first exception because it can contain useful error message
    if (firstActionExecutionException != null) {
      if (missingCount == 0 && actionFailures == 1) {
        // In the case a single action failed, just propagate the exception upward. This avoids
        // having to copy the root causes to the upwards transitive closure.
        throw firstActionExecutionException;
      }
      throw new ActionExecutionException(
          firstActionExecutionException.getMessage(),
          firstActionExecutionException.getCause(),
          action,
          rootCauses.build(),
          firstActionExecutionException.isCatastrophe(),
          firstActionExecutionException.getExitCode());
    }

    if (missingCount > 0) {
      for (Cause missingInput : rootCauses.build()) {
        env.getListener()
            .handle(
                Event.error(
                    action.getOwner().getLocation(),
                    String.format(
                        "%s: missing input file '%s'",
                        action.getOwner().getLabel(), missingInput.getLabel())));
      }
      throw new ActionExecutionException(missingCount + " input file(s) do not exist", action,
          rootCauses.build(), /*catastrophe=*/false);
    }
    return Pair.of(inputArtifactData, expandedArtifacts);
  }

  private static Iterable<Artifact> filterKnownInputs(
      Iterable<Artifact> newInputs, MutableInputArtifactData inputArtifactData) {
    return Iterables.filter(newInputs, input -> !inputArtifactData.contains(input));
  }

  /**
   * All info/warning messages associated with actions should be always displayed.
   */
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  /**
   * Exception to be thrown if an action is missing Skyframe dependencies that it finds are missing
   * during execution/input discovery.
   */
  public static class MissingDepException extends RuntimeException {}

  /**
   * Should be called once execution is over, and the intra-build cache of in-progress computations
   * should be discarded. If the cache is non-empty (due to an interrupted/failed build), failure to
   * call complete() can both cause a memory leak and incorrect results on the subsequent build.
   */
  @Override
  public void complete() {
    // Discard all remaining state (there should be none after a successful execution).
    stateMap = Maps.newConcurrentMap();
  }

  private ContinuationState getState(Action action) {
    ContinuationState state = stateMap.get(action);
    if (state == null) {
      state = new ContinuationState();
      Preconditions.checkState(stateMap.put(action, state) == null, action);
    }
    return state;
  }

  /**
   * State to save work across restarts of ActionExecutionFunction due to missing values in the
   * graph for actions that discover inputs. There are three places where we save work, all for
   * actions that discover inputs:
   * <ol>
   *   <li>If not all known input metadata (coming from Action#getInputs) is available yet, then the
   *   calculated set of inputs (including the inputs resolved from the action cache) is saved.</li>
   *   <li>If not all discovered inputs' metadata is available yet, then the known input metadata
   *   together with the set of discovered inputs is saved, as well as the Token used to identify
   *   this action to the action cache.</li>
   *   <li>If, after execution, new inputs are discovered whose metadata is not yet available, then
   *   the same data as in the previous case is saved, along with the actual result of execution.
   *   </li>
   * </ol>
   */
  private static class ContinuationState {
    AllInputs allInputs;
    /** Mutable map containing metadata for known artifacts. */
    MutableInputArtifactData inputArtifactData = null;

    Map<Artifact, Collection<Artifact>> expandedArtifacts = null;
    Token token = null;
    Iterable<Artifact> discoveredInputs = null;
    Iterable<Artifact> discoveredInputsStage2 = null;
    ActionExecutionValue value = null;
    ActionFileSystem actionFileSystem = null;

    boolean hasCollectedInputs() {
      return allInputs != null;
    }

    boolean hasArtifactData() {
      boolean result = inputArtifactData != null;
      Preconditions.checkState(result == (expandedArtifacts != null), this);
      return result;
    }

    boolean hasCheckedActionCache() {
      // If token is null because there was an action cache hit, this method is never called again
      // because we return immediately.
      return token != null;
    }

    boolean hasExecutedAction() {
      return value != null;
    }

    /** Must be called to assign values to the given variables as they change. */
    void updateFileSystemContext(
        SkyFunction.Environment env, ActionMetadataHandler metadataHandler) {
      if (actionFileSystem != null) {
        actionFileSystem.updateContext(env, metadataHandler::injectOutputData);
      }
    }

    @Override
    public String toString() {
      return token + ", " + value + ", " + allInputs + ", " + inputArtifactData + ", "
          + discoveredInputs;
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by
   * {@link ActionExecutionFunction#compute}.
   */
  private static final class ActionExecutionFunctionException extends SkyFunctionException {

    private final ActionExecutionException actionException;

    public ActionExecutionFunctionException(ActionExecutionException e) {
      // We conservatively assume that the error is transient. We don't have enough information to
      // distinguish non-transient errors (e.g. compilation error from a deterministic compiler)
      // from transient ones (e.g. IO error).
      // TODO(bazel-team): Have ActionExecutionExceptions declare their transience.
      super(e, Transience.TRANSIENT);
      this.actionException = e;
    }

    @Override
    public boolean isCatastrophic() {
      return actionException.isCatastrophe();
    }
  }
}
