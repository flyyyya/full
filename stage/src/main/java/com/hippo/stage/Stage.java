/*
 * Copyright 2017 Hippo Seven
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

package com.hippo.stage;

/*
 * Created by Hippo on 4/20/2017.
 */

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * A {@code Stage} is where {@link Scene}s performed.
 * {@code Stage} objects handle attaching and detaching views of {@link Scene}s.
 */
public final class Stage implements Iterable<Scene> {

  private static final String LOG_TAG = Scene.class.getSimpleName();

  private static final boolean DEBUG = BuildConfig.DEBUG;

  static final String KEY_ID = "Stage:id";
  private static final String KEY_STACK = "Stage:stack";

  private Director director;
  private int id;
  private ViewGroup container;
  private CurtainSuppler curtainSuppler;
  private Curtain runningCurtain;
  private SceneStack stack = new SceneStack(new SceneStack.Callback() {
    @Override
    public void onPush(@NonNull Scene scene) {
      onPushScene(scene);
    }
    @Override
    public void onPop(@NonNull Scene scene, boolean willRecreate) {
      onPopScene(scene, willRecreate);
    }
  });

  private boolean isStarted;
  private boolean isResumed;
  private boolean isDestroyed;
  private boolean willRecreate;

  private boolean isRunningOperation;
  private boolean isOperatingDelayedOperations;
  private Queue<Operation> delayedOperations = new LinkedList<>();
  private Operator pop;
  private Operator push;
  private Operator replaceTop;
  private Operator setRoot;

  private final SparseIntArray activityRequestCodeMap = new SparseIntArray();
  private final SparseIntArray permissionRequestCodeMap = new SparseIntArray();

  private boolean willPopTheLastScene;
  private BackHandler<Stage> backHandler;

  Stage(Director director) {
    this.director = director;
  }

  Director getDirector() {
    return director;
  }

  void setId(int id) {
    this.id = id;
  }

  int getId() {
    return id;
  }

  /**
   * Pops the top {@link Scene}.
   * It's a no-op if scene stack is empty.
   */
  public void popTopScene() {
    Scene scene = stack.peek();
    if (scene != null) {
      popScene(scene);
    } else {
      Log.w(LOG_TAG, "Can't pop an empty Stage");
    }
  }

  /**
   * Pop a {@link Scene}.
   * It's a no-op if scene isn't in the stack.
   */
  void popScene(@NonNull Scene scene) {
    if (pop == null) {
      pop = new Pop();
    }
    operate(scene, pop);
  }

  /**
   * Push a {@link Scene} to the top of the stack.
   */
  public void pushScene(@NonNull Scene scene) {
    if (push == null) {
      push = new Push();
    }
    operate(scene, push);
  }

  /**
   * Replace the top {@link Scene} with a {@code Scene}.
   * If the stack is empty, just push the {@code Scene}.
   */
  public void replaceTopScene(@NonNull Scene scene) {
    if (replaceTop == null) {
      replaceTop = new ReplaceTop();
    }
    operate(scene, replaceTop);
  }

  /**
   * Pops all {@link Scene}s in the stack, push a {@link Scene} as root.
   */
  public void setRootScene(@NonNull Scene scene) {
    if (setRoot == null) {
      setRoot = new SetRoot();
    }
    operate(scene, setRoot);
  }

  private void operate(@NonNull Scene scene, @NonNull Operator operator) {
    if (isDestroyed) {
      Log.e(LOG_TAG, "Can't call pushScene() or popScene() on a destroyed Scene");
      return;
    }

    if (isRunningOperation) {
      // An Operator is running now, delay this one
      delayedOperations.offer(new Operation(scene, operator));
      return;
    }

    isRunningOperation = true;
    completeRunningCurtain();
    operator.operate(scene);
    isRunningOperation = false;

    if (!isOperatingDelayedOperations) {
      // Operate delayed operators, lock it to avoid it called in loop
      isOperatingDelayedOperations = true;

      Operation operation;
      while ((operation = delayedOperations.poll()) != null) {
        isRunningOperation = true;
        completeRunningCurtain();
        operation.getOperator().operate(operation.getScene());
        isRunningOperation = false;
      }

      isOperatingDelayedOperations = false;
    }
  }

  /**
   * Returns {@code true} if there is a running {@link Curtain}, or {@code false}.
   */
  public boolean hasCurtainRunning() {
    return runningCurtain != null;
  }

  /**
   * Completes running {@link Curtain}.
   */
  public void completeRunningCurtain() {
    if (runningCurtain != null) {
      runningCurtain.completeImmediately();

      // Check whether Curtain.OnCompleteListener is called
      if (runningCurtain != null) {
        throw new IllegalStateException("Must call"
            + " OnCompleteListener.onChangeCompleted() in "
            + runningCurtain.getClass().getName() + ".completeImmediately()");
      }
    }
  }

  @Nullable
  Curtain requestCurtain(@NonNull SceneInfo upper, @NonNull List<SceneInfo> lower) {
    Curtain curtain = null;
    if (curtainSuppler != null) {
      curtain = curtainSuppler.getCurtain(upper, lower);
    }
    if (curtain == null && director != null) {
      return director.requestCurtain(upper, lower);
    }
    return curtain;
  }

  @Nullable
  private Curtain getCurtain(@NonNull SceneInfo upper, @NonNull List<SceneInfo> lower) {
    Curtain curtain = upper.scene.onCreateCurtain(upper, lower);
    if (curtain == null) {
      curtain = requestCurtain(upper, lower);
    }
    return curtain;
  }

  private void changeScenes(
      @NonNull final SceneInfo upper, @NonNull final List<SceneInfo> lower) {
    Curtain curtain = getCurtain(upper, lower);
    if (curtain != null) {
      runningCurtain = curtain;
      curtain.execute(container, upper, lower, new Curtain.OnCompleteListener() {
        @Override
        public void onComplete() {
          runningCurtain = null;
          detachViewIfNecessary(upper);
          detachViewsIfNecessary(lower);
        }
      });
    } else {
      detachViewIfNecessary(upper);
      detachViewsIfNecessary(lower);
    }
  }

  private void detachViewIfNecessary(@NonNull SceneInfo info) {
    if (info.viewState == SceneInfo.WILL_BE_DETACHED) {
      Scene scene = info.scene;
      if (info.isStarted) {
        scene.stop();
      }
      scene.detachView(container);
    }
  }

  private void detachViewsIfNecessary(
      @NonNull final List<SceneInfo> infos) {
    for (SceneInfo info : infos) {
      detachViewIfNecessary(info);
    }
  }

  int requireSceneId() {
    return director.requireSceneId();
  }

  private void onPushScene(@NonNull Scene scene) {
    int id = scene.getSavedId();
    if (id == Scene.INVALID_ID) {
      id = requireSceneId();
    }
    scene.create(this, id);
  }

  private void onPopScene(@NonNull Scene scene, boolean willRecreate) {
    scene.finish(willRecreate);
  }

  @NonNull
  private ArrayList<Scene> getVisibleScenes() {
    ArrayList<Scene> scenes = new ArrayList<>();
    boolean isTop = true;
    for (Scene scene : stack) {
      scenes.add(scene);
      int opacity = scene.getOpacity();
      if (opacity == Scene.OPAQUE || (opacity == Scene.TRANSLUCENT && !isTop)) {
        // The scenes below can't be seen
        break;
      }
      // It's not top anymore
      isTop = false;
    }
    return scenes;
  }

  /**
   * Returns {@code true} if this Stage has a container.
   */
  public boolean hasContainer() {
    return container != null;
  }

  void setContainer(@NonNull ViewGroup container) {
    if (DEBUG) {
      assertNull(this.container);
      assertNotNull(container);
      assertFalse(isDestroyed);
    }

    this.container = container;
    if (container instanceof StageLayout) {
      ((StageLayout) container).addStage(this);
    }

    // Restore views
    ArrayList<Scene> visible = getVisibleScenes();
    for (int i = visible.size() - 1; i >= 0; --i) {
      Scene scene = visible.get(i);
      scene.attachView(container);
      if (isStarted) {
        scene.start();
      }
      if (isResumed && i == 0) {
        scene.resume();
      }
    }
  }

  ViewGroup getContainer() {
    return container;
  }

  void start() {
    if (DEBUG) {
      assertFalse(isStarted);
      assertFalse(isResumed);
    }

    isStarted = true;

    if (container != null) {
      // All visible scenes should start
      for (Scene scene : getVisibleScenes()) {
        scene.start();
      }
    }
  }

  void resume() {
    if (DEBUG) {
      assertTrue(isStarted);
      assertFalse(isResumed);
    }

    isResumed = true;

    if (container != null) {
      // Only top scene should resume
      Scene scene = getTopScene();
      if (scene != null) {
        scene.resume();
      }
    }
  }

  void pause() {
    if (DEBUG) {
      assertTrue(isStarted);
      assertTrue(isResumed);
    }

    isResumed = false;

    if (container != null) {
      // Only top scene should pause
      Scene scene = getTopScene();
      if (scene != null) {
        scene.pause();
      }
    }
  }

  void stop() {
    if (DEBUG) {
      assertTrue(isStarted);
      assertFalse(isResumed);
    }

    isStarted = false;

    if (container != null) {
      // All visible scenes should stop
      for (Scene scene : getVisibleScenes()) {
        scene.stop();
      }
    }
  }

  void setWillRecreate() {
    willRecreate = true;
  }

  void detach() {
    if (DEBUG) {
      assertFalse(isStarted);
      assertFalse(isResumed);
    }

    completeRunningCurtain();

    if (container != null) {
      for (Scene scene : getVisibleScenes()) {
        scene.detachView(container, true);
      }

      // The activity is destroyed, can't attach views to this container
      if (container instanceof StageLayout) {
        ((StageLayout) container).removeStage(this);
      }
      container = null;
    }
  }

  void destroy() {
    if (DEBUG) {
      assertNull(container);
    }

    isDestroyed = true;
    stack.popAll(willRecreate);
    director = null;
  }

  /**
   * Saves state of this {@code Stage}.
   * It could be used for {@link Director#direct(ViewGroup, Bundle)}.
   */
  public void saveInstanceState(@NonNull Bundle outState) {
    outState.putInt(KEY_ID, id);

    Bundle stackState = new Bundle();
    stack.saveInstanceState(stackState);
    outState.putBundle(KEY_STACK, stackState);
  }

  // TODO restoreInstanceState() is always called after new Stage(), why not move it to the constructor?
  void restoreInstanceState(@NonNull Bundle savedInstanceState) {
    id = savedInstanceState.getInt(KEY_ID);

    Bundle bundle = savedInstanceState.getBundle(KEY_STACK);
    if (bundle != null) {
      // Only push Scenes to stack
      stack.restoreInstanceState(bundle);
      // setContainer() should be called soon, let it handle view attaching
    }
  }

  /**
   * Detaches this Stage from it's container, but keeps all state.
   * <p>
   * It is useful when a Stage should be invisible temporarily or
   * need another container.
   * <p>
   * Uses {@link #restore(ViewGroup)} to set another container.
   * <p>
   * It's a no-op if the Stage has no container.
   *
   * @see #restore(ViewGroup)
   * @see #hasContainer()
   */
  public void suspend() {
    if (container == null) {
      return;
    }

    completeRunningCurtain();

    if (isResumed) {
      // Only top scene should pause
      Scene scene = getTopScene();
      if (scene != null) {
        scene.pause();
      }
    }

    // All visible scenes should be stopped and detached
    for (Scene scene : getVisibleScenes()) {
      if (isStarted) {
        scene.stop();
      }
      scene.detachView(container, true);
    }

    // Detach from the container
    if (container instanceof StageLayout) {
      ((StageLayout) container).removeStage(this);
    }
    container = null;
  }

  /**
   * Restore a suspended Stage with the container.
   * <p>
   * If the Stage has a container, throw IllegalStateException.
   */
  // TODO merge setContainer() to restore()
  public void restore(@NonNull ViewGroup container) {
    if (this.container == null) {
      setContainer(container);
    } else if (this.container != container) {
      throw new IllegalStateException("The Stage isn't suspended, can't be restored");
    }
  }

  /**
   * Destroys this {@code Stage}.
   * This Stage will be removed from its host {@link Director}.
   * All {@link Scene}s in its stack will be popped.
   * Don't do any operation on a destroyed Stage.
   */
  public void close() {
    if (director != null) {
      director.close(this);
    }
  }

  /**
   * Requests focus for this {@code Stage}.
   * It affects its host {@link Director#handleBack()}.
   */
  public void requestFocus() {
    if (director != null) {
      director.setFocusedStage(this);
    }
  }

  /**
   * Sets whether the last scene will popped in {@link #handleBack()}.
   * {@code false} in default.
   */
  public void setWillPopTheLastScene(boolean willPopTheLastScene) {
    this.willPopTheLastScene = willPopTheLastScene;
  }

  /**
   * Returns the value whether the last scene will popped in {@link #handleBack()}.
   */
  public boolean willPopTheLastScene() {
    return willPopTheLastScene;
  }

  /**
   * Sets a {@link BackHandler} for this {@code Stage}.
   * It overrides the default back action handling method.
   */
  public void setBackHandler(BackHandler<Stage> backHandler) {
    this.backHandler = backHandler;
  }

  /**
   * Handle the back button being pressed.
   * Returns {@code true} if the back action is consumed.
   * <p>
   * The method calls {@link #onHandleBack()} directly, but it could be override by setting
   * a {@link BackHandler}.
   *
   * @see #setBackHandler(BackHandler)
   * @see #onHandleBack()
   */
  public boolean handleBack() {
    if (backHandler != null) {
      return backHandler.handleBack(this);
    } else {
      return onHandleBack();
    }
  }

  /**
   * This method is how a {@code Stage} handles back action in default.
   * Returns {@code true} if the back action is consumed.
   * <p>
   * It calls {@link Scene#handleBack()} on the top {@link Scene}.
   * If the back action isn't consumed, it pops the top {@code Scene}.
   * If {@link #willPopTheLastScene()} returns {@code false}, it
   * keeps the last {@code Scene}.
   */
  public boolean onHandleBack() {
    Scene scene = getTopScene();
    if (scene != null && scene.handleBack()) {
      return true;
    } else {
      int maxSceneCount = willPopTheLastScene ? 0 : 1;
      if (stack.size() > maxSceneCount) {
        popTopScene();
        return true;
      } else {
        return false;
      }
    }
  }

  /**
   * Look for the first child {@link Scene} with the given tag in the scene stack.
   */
  @Nullable
  public Scene findSceneByTag(@NonNull String tag) {
    for (Scene scene : stack) {
      if (tag.equals(scene.getTag())) {
        return scene;
      }
    }
    return null;
  }

  /**
   * Look for a child {@link Scene} with the given id in the scene sub-hierarchy.
   */
  @Nullable
  public Scene findSceneById(int sceneId) {
    for (Scene scene : stack) {
      Scene result = scene.findSceneById(sceneId);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  /**
   * Returns top {@link Scene} in stack.
   */
  @Nullable
  public Scene getTopScene() {
    return stack.peek();
  }

  /**
   * Returns root {@link Scene} in stack.
   */
  @Nullable
  public Scene getRootScene() {
    return stack.tail();
  }

  /**
   * Returns an unmodified Scene iterator which is from top to root.
   */
  @NonNull
  @Override
  public Iterator<Scene> iterator() {
    return new Iterator<Scene>() {

      private Iterator<Scene> iterator = stack.iterator();

      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      public Scene next() {
        return iterator.next();
      }
    };
  }

  /**
   * Returns the number of {@link Scene}s in {@code Stage}.
   */
  public int getSceneCount() {
    return stack.size();
  }

  /**
   * Sets the curtain suppler for this {@code Stage}.
   */
  public void setCurtainSuppler(CurtainSuppler suppler) {
    this.curtainSuppler = suppler;
  }

  boolean willDestroyActivity() {
    return director == null || director.willDestroyActivity();
  }

  /**
   * Return the {@link Activity} this {@code Stage} is currently associated with.
   */
  @Nullable
  Activity getActivity() {
    return director != null ? director.getActivity() : null;
  }

  void startActivity(@NonNull Intent intent) {
    if (director != null) {
      director.startActivity(intent);
    }
  }

  @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
  void startActivity(@NonNull Intent intent, @Nullable Bundle options) {
    if (director != null) {
      director.startActivity(intent, options);
    }
  }

  void startActivityForResult(int sceneId, Intent intent, int requestCode) {
    if (director != null) {
      // TODO check duplicate request code
      activityRequestCodeMap.put(requestCode, sceneId);
      director.startActivityForResult(id, intent, requestCode);
    }
  }

  @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
  void startActivityForResult(int sceneId, Intent intent, int requestCode, Bundle options) {
    if (director != null) {
      // TODO check duplicate request code
      activityRequestCodeMap.put(requestCode, sceneId);
      director.startActivityForResult(id, intent, requestCode, options);
    }
  }

  void onActivityResult(int requestCode, int resultCode, Intent data) {
    int index = activityRequestCodeMap.indexOfKey(requestCode);
    if (index >= 0) {
      int sceneId = activityRequestCodeMap.valueAt(index);
      activityRequestCodeMap.removeAt(index);
      Scene scene = findSceneById(sceneId);
      if (scene != null) {
        scene.onActivityResult(requestCode, resultCode, data);
      }
    }
  }

  void requestPermissions(int sceneId, @NonNull String[] permissions, int requestCode) {
    if (director != null) {
      // TODO check duplicate request code
      permissionRequestCodeMap.put(requestCode, sceneId);
      director.requestPermissions(id, permissions, requestCode);
    }
  }

  void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    int index = permissionRequestCodeMap.indexOfKey(requestCode);
    if (index >= 0) {
      int sceneId = permissionRequestCodeMap.valueAt(index);
      permissionRequestCodeMap.removeAt(index);
      Scene scene = findSceneById(sceneId);
      if (scene != null) {
        scene.onRequestPermissionsResult(requestCode, permissions, grantResults);
      }
    }
  }

  // A Operation is used for delayed popping or pushing or something like that
  private static final class Operation {

    private final Scene scene;
    private final Operator operator;

    Operation(@NonNull Scene scene, @NonNull Operator operator) {
      this.scene = scene;
      this.operator = operator;
    }

    @NonNull
    Scene getScene() {
      return scene;
    }

    @NonNull
    Operator getOperator() {
      return operator;
    }
  }

  // A Operator handles Scene popping or pushing or something like that
  private abstract class Operator {

    void operate(@NonNull Scene scene) {
      if (withViews(scene)) {
        operateWithViews(scene);
      } else {
        operateWithoutViews(scene);
      }
    }

    abstract boolean withViews(@NonNull Scene scene);

    abstract void operateWithViews(@NonNull Scene scene);

    abstract void operateWithoutViews(@NonNull Scene scene);
  }

  private class Pop extends Operator {

    @Override
    boolean withViews(@NonNull Scene scene) {
      // If this Scene isn't view attached, popping it can't affect other attached Scenes
      return container != null && scene.getLifecycleState().isViewAttached();
    }

    @Override
    void operateWithViews(@NonNull Scene scene) {
      int index = stack.pop(scene);
      if (index == SceneStack.INVALID_INDEX) {
        // Delayed pop operation might be operated after the scene has been popped
        return;
      }

      // Calculate the visible scenes below popped scene
      List<Scene> newScenes = getVisibleScenes();

      if (index == 0 && isResumed) {
        scene.pause();
      }
      SceneInfo upper = new SceneInfo.Builder()
          .scene(scene)
          .viewState(SceneInfo.WILL_BE_DETACHED)
          .isStarted(isStarted)
          .build();

      int opacity = scene.getOpacity();
      boolean newlyAttached = opacity == Scene.OPAQUE || (opacity == Scene.TRANSLUCENT && index != 0);
      boolean isTop = index == 0;
      List<Scene> lowerScenes = newScenes.subList(index, newScenes.size());
      List<SceneInfo> lower = new ArrayList<>(lowerScenes.size());
      for (Scene lowerScene : lowerScenes) {

        if (newlyAttached) {
          lowerScene.attachView(container, 0);
          if (isStarted) {
            lowerScene.start();
          }
        }
        // Only top scene can be resumed
        if (isResumed && isTop) {
          lowerScene.resume();
        }

        SceneInfo lowerInfo = new SceneInfo.Builder()
            .scene(lowerScene)
            .viewState(newlyAttached ? SceneInfo.NEWLY_ATTACHED : SceneInfo.NONE)
            .isStarted(isStarted)
            .build();
        lower.add(lowerInfo);

        if (isTop && lowerScene.getOpacity() == Scene.TRANSLUCENT) {
          // An translucent scene become top now, the following scene must be newly attached
          newlyAttached = true;
        }

        // Not top anymore
        isTop = false;
      }

      changeScenes(upper, lower);
    }

    @Override
    void operateWithoutViews(@NonNull Scene scene) {
      stack.pop(scene);
    }
  }

  private class Push extends Operator {

    @Override
    boolean withViews(@NonNull Scene scene) {
      return container != null;
    }

    @Override
    void operateWithViews(@NonNull Scene scene) {
      ArrayList<Scene> oldScenes = getVisibleScenes();
      stack.push(scene);
      ArrayList<Scene> newScenes = getVisibleScenes();

      scene.attachView(container);
      if (isStarted) {
        scene.start();
      }
      // It's top
      if (isResumed) {
        scene.resume();
      }
      SceneInfo upper = new SceneInfo.Builder()
          .scene(scene)
          .viewState(SceneInfo.NEWLY_ATTACHED)
          .isStarted(isStarted)
          .build();

      List<SceneInfo> lower = new ArrayList<>(oldScenes.size());
      // Some Scenes in bottom might be not visible
      // start from (newScenes.size() - 1) in oldScenes
      int detachIndex = newScenes.size() - 1;
      for (int i = 0, n = oldScenes.size(); i < n; ++i) {
        Scene lowerScene = oldScenes.get(i);

        // Pause the original top Scene if activity is resumed
        if (i == 0 && isResumed) {
          lowerScene.pause();
        }

        SceneInfo lowerInfo = new SceneInfo.Builder()
            .scene(lowerScene)
            .viewState(i >= detachIndex ? SceneInfo.WILL_BE_DETACHED : SceneInfo.NONE)
            .isStarted(isStarted)
            .build();
        lower.add(lowerInfo);
      }

      changeScenes(upper, lower);
    }

    @Override
    void operateWithoutViews(@NonNull Scene scene) {
      stack.push(scene);
    }
  }

  private class ReplaceTop extends Operator {

    @Override
    boolean withViews(@NonNull Scene scene) {
      return container != null;
    }

    @Override
    void operateWithViews(@NonNull Scene scene) {
      ArrayList<Scene> oldScenes = getVisibleScenes();
      Scene oldTopScene = stack.pop();
      stack.push(scene);
      ArrayList<Scene> newScenes = getVisibleScenes();

      if (DEBUG) {
        if (oldTopScene == null) {
          assertEquals(0, oldScenes.size());
        }
        assertTrue(newScenes.size() > 0);
      }

      scene.attachView(container);
      if (isStarted) {
        scene.start();
      }
      // It's top
      if (isResumed) {
        scene.resume();
      }
      SceneInfo upper = new SceneInfo.Builder()
          .scene(scene)
          .viewState(SceneInfo.NEWLY_ATTACHED)
          .isStarted(isStarted)
          .build();

      if (oldTopScene == null) {
        // If oldTopScene == null, add a null to make following works
        oldScenes.add(null);
      }
      int lowerSize = Math.max(oldScenes.size(), newScenes.size());
      List<SceneInfo> lower = new ArrayList<>(lowerSize);
      if (oldTopScene != null) {
        // Add old top scenes if it's not null
        if (isResumed) {
          oldTopScene.pause();
        }
        SceneInfo lowerInfo = new SceneInfo.Builder()
            .scene(oldTopScene)
            .viewState(SceneInfo.WILL_BE_DETACHED)
            .isStarted(isStarted)
            .build();
        lower.add(lowerInfo);
      }
      // Add the same scenes which are in both old visible scenes and new visible scenes
      int sameSize = Math.min(oldScenes.size(), newScenes.size());
      for (int i = 1; i < sameSize; ++i) {
        Scene lowerScene = oldScenes.get(i);
        SceneInfo lowerInfo = new SceneInfo.Builder()
            .scene(lowerScene)
            .viewState(SceneInfo.NONE)
            .isStarted(isStarted)
            .build();
        lower.add(lowerInfo);
      }
      // Add scenes which will be detached
      if (oldScenes.size() > sameSize) {
        for (int i = sameSize; i < oldScenes.size(); ++i) {
          Scene lowerScene = oldScenes.get(i);
          SceneInfo lowerInfo = new SceneInfo.Builder()
              .scene(lowerScene)
              .viewState(SceneInfo.WILL_BE_DETACHED)
              .isStarted(isStarted)
              .build();
          lower.add(lowerInfo);
        }
      }
      // Add scenes which should be attached
      if (newScenes.size() > sameSize) {
        for (int i = sameSize; i < newScenes.size(); ++i) {
          Scene lowerScene = newScenes.get(i);
          // Always attach view to tail
          lowerScene.attachView(container, 0);
          if (isStarted) {
            lowerScene.start();
          }
          SceneInfo lowerInfo = new SceneInfo.Builder()
              .scene(lowerScene)
              .viewState(SceneInfo.NEWLY_ATTACHED)
              .isStarted(isStarted)
              .build();
          lower.add(lowerInfo);
        }
      }

      changeScenes(upper, lower);
    }

    @Override
    void operateWithoutViews(@NonNull Scene scene) {
      stack.pop();
      stack.push(scene);
    }
  }

  private class SetRoot extends Operator {

    @Override
    boolean withViews(@NonNull Scene scene) {
      return container != null;
    }

    @Override
    void operateWithViews(@NonNull Scene scene) {
      ArrayList<Scene> oldScenes = getVisibleScenes();
      stack.popAll(false);
      stack.push(scene);

      scene.attachView(container);
      if (isStarted) {
        scene.start();
      }
      // It's top
      if (isResumed) {
        scene.resume();
      }
      SceneInfo upper = new SceneInfo.Builder()
          .scene(scene)
          .viewState(SceneInfo.NEWLY_ATTACHED)
          .isStarted(isStarted)
          .build();

      List<SceneInfo> lower = new ArrayList<>(oldScenes.size());
      boolean isTop = true;
      for (Scene lowerScene : oldScenes) {
        if (isResumed && isTop) {
          lowerScene.pause();
        }

        SceneInfo lowerInfo = new SceneInfo.Builder()
            .scene(lowerScene)
            .viewState(SceneInfo.WILL_BE_DETACHED)
            .isStarted(isStarted)
            .build();
        lower.add(lowerInfo);

        // Not top anymore
        isTop = false;
      }

      changeScenes(upper, lower);
    }

    @Override
    void operateWithoutViews(@NonNull Scene scene) {
      stack.popAll(false);
      stack.push(scene);
    }
  }
}
