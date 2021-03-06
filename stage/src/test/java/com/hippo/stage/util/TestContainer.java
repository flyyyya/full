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

package com.hippo.stage.util;

/*
 * Created by Hippo on 4/22/2017.
 */

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.support.annotation.AttrRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class TestContainer extends FrameLayout {

  public TestContainer(@NonNull Context context) {
    super(context);
  }

  public TestContainer(@NonNull Context context,
      @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  public TestContainer(@NonNull Context context,
      @Nullable AttributeSet attrs, @AttrRes int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  /**
   * @param ids from top to root
   */
  public void assertChildren(int... ids) {
    assertEquals(ids.length, getChildCount());

    int index = ids.length;
    while (--index >= 0) {
      assertEquals("index of child = " + index, ids[index], getChildAt(index).getId());
    }
  }
}
