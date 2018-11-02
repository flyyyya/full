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

package com.hippo.stage.demo;

/*
 * Created by Hippo on 5/2/2017.
 */

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

public class Utils {
  private Utils() {}

  @SuppressWarnings("unchecked")
  @NonNull
  public static <T> T newInstance(Class clazz) {
    try {
      return (T) clazz.newInstance();
    } catch (Throwable e) {
      throw new RuntimeException("Can't create a new instance of " + clazz, e);
    }
  }

  public static Bundle putBoolean(Bundle bundle, @Nullable String key, boolean value) {
    bundle.putBoolean(key, value);
    return bundle;
  }

  public static Bundle putInt(Bundle bundle, @Nullable String key, int value) {
    bundle.putInt(key, value);
    return bundle;
  }
}
