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
 * Created by Hippo on 4/22/2017.
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.hippo.stage.util.TestContainer;
import com.hippo.stage.util.TestView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class StageTest {

  private Director director;
  private Stage stage;

  @Before
  public void before() {
    director = new DumpDirector();
    stage = new Stage(director);
    stage.setContainer(new TestContainer(RuntimeEnvironment.application));
  }

  @Test
  public void testGetTopScene() {
    Scene scene1 = new TestScene();
    Scene scene2 = new TestScene();

    assertNull(stage.getTopScene());

    stage.pushScene(scene1);
    assertEquals(scene1, stage.getTopScene());

    stage.pushScene(scene2);
    assertEquals(scene2, stage.getTopScene());
  }

  @Test
  public void testGetRootScene() {
    Scene scene1 = new TestScene();
    Scene scene2 = new TestScene();

    assertNull(stage.getRootScene());

    stage.pushScene(scene1);
    assertEquals(scene1, stage.getRootScene());

    stage.pushScene(scene2);
    assertEquals(scene1, stage.getRootScene());
  }

  @Test
  public void testGetSceneCount() {
    Scene scene1 = new TestScene();
    Scene scene2 = new TestScene();

    assertEquals(0, stage.getSceneCount());

    stage.pushScene(scene1);
    assertEquals(1, stage.getSceneCount());

    stage.pushScene(scene2);
    assertEquals(2, stage.getSceneCount());

    stage.popScene(scene1);
    assertEquals(1, stage.getSceneCount());
  }

  @Test
  public void testPushWithoutViews() {
    Stage stage = new Stage(director);

    stage.pushScene(new TestScene());
    assertEquals(1, stage.getSceneCount());

    stage.pushScene(new TestScene());
    assertEquals(2, stage.getSceneCount());
  }

  @Test
  public void testPopWithoutViews() {
    Stage stage = new Stage(director);

    Scene scene1 = new TestScene();
    Scene scene2 = new TestScene();
    Scene scene3 = new TestScene();

    stage.pushScene(scene1);
    stage.pushScene(scene2);
    stage.pushScene(scene3);
    assertEquals(3, stage.getSceneCount());

    stage.popScene(scene2);
    assertEquals(2, stage.getSceneCount());

    stage.popScene(scene3);
    assertEquals(1, stage.getSceneCount());

    stage.popScene(scene1);
    assertEquals(0, stage.getSceneCount());

    stage.popScene(scene2);
    assertEquals(0, stage.getSceneCount());
  }

  @Test
  public void testReplaceTopWithoutViews() {
    Stage stage = new Stage(director);

    stage.pushScene(new TestScene());
    stage.pushScene(new TestScene());
    stage.pushScene(new TestScene());
    assertEquals(3, stage.getSceneCount());

    stage.replaceTopScene(new TestScene());
    assertEquals(3, stage.getSceneCount());
  }

  @Test
  public void testSetRootWithoutViews() {
    Stage stage = new Stage(director);

    stage.pushScene(new TestScene());
    stage.pushScene(new TestScene());
    stage.pushScene(new TestScene());
    assertEquals(3, stage.getSceneCount());

    stage.setRootScene(new TestScene());
    assertEquals(1, stage.getSceneCount());
  }

  @Test
  public void testPopPushAfterDestroyed() {
    stage.detach(false);
    stage.destroy();
    assertEquals(0, stage.getSceneCount());

    stage.pushScene(new TestScene());
    assertEquals(0, stage.getSceneCount());

    stage.popScene(new TestScene());
    assertEquals(0, stage.getSceneCount());

    stage.replaceTopScene(new TestScene());
    assertEquals(0, stage.getSceneCount());

    stage.setRootScene(new TestScene());
    assertEquals(0, stage.getSceneCount());
  }

  @Test
  public void testPopPushWhenOperating() {
    final Scene scene1 = new TestScene();
    final Scene scene2 = new TestScene();
    final Scene scene3 = new TestScene();

    stage.pushScene(new Scene() {
      @Override
      protected void onCreate(@NonNull Bundle args) {
        super.onCreate(args);

        Stage stage = getStage();
        stage.pushScene(scene1);
        stage.pushScene(new Scene() {
          @Override
          protected void onCreate(@NonNull Bundle args) {
            super.onCreate(args);

            Stage stage = getStage();
            stage.pushScene(scene2);
          }

          @NonNull
          @Override
          protected View onCreateView(@NonNull LayoutInflater inflater,
              @NonNull ViewGroup container) {
            return new TestView(inflater.getContext());
          }
        });
        stage.replaceTopScene(scene3);
      }

      @NonNull
      @Override
      protected View onCreateView(@NonNull LayoutInflater inflater, @NonNull ViewGroup container) {
        return new TestView(inflater.getContext());
      }
    });

    assertEquals(4, stage.getSceneCount());
    assertEquals(scene2, stage.getTopScene());
  }

  @Test
  public void testSuspendAndRestore() {
    ViewGroup container = new TestContainer(RuntimeEnvironment.application);

    Stage stage = new Stage(new DumpDirector());
    stage.start();
    stage.resume();

    stage.setContainer(container);

    Scene scene1 = new TestScene();
    Scene scene2 = new TestScene();
    Scene scene3 = new TestScene();

    stage.pushScene(scene1);
    stage.pushScene(scene2);
    stage.pushScene(scene3);

    assertFalse(container.getChildCount() == 0);
    assertTrue(scene3.getLifecycleState().isResumed());
    assertTrue(scene3.getLifecycleState().isStarted());

    stage.suspend();
    assertTrue(container.getChildCount() == 0);
    assertFalse(scene3.getLifecycleState().isResumed());
    assertFalse(scene3.getLifecycleState().isStarted());

    stage.restore(container);
    assertFalse(container.getChildCount() == 0);
    assertTrue(scene3.getLifecycleState().isResumed());
    assertTrue(scene3.getLifecycleState().isStarted());
  }

  @Test
  public void testDelayedPopAfterPop() {
    ViewGroup container = new TestContainer(RuntimeEnvironment.application);

    Stage stage = new Stage(new DumpDirector());
    stage.start();
    stage.resume();

    stage.setContainer(container);

    Scene scene = new Scene() {
      @Override
      protected void onCreate(@NonNull Bundle args) {
        super.onCreate(args);
        pop();
        pop();
      }
      @NonNull
      @Override
      protected View onCreateView(@NonNull LayoutInflater inflater, @NonNull ViewGroup container) {
        return new TestView(inflater.getContext());
      }
    };

    stage.pushScene(scene);
  }
}
