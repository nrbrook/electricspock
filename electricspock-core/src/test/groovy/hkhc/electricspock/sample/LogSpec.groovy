/*
 * Copyright 2016 Herman Cheung
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
 *
 */

package hkhc.electricspock.sample

import android.os.Build
import android.util.Log
import hkhc.electricspock.ElectricSpecification
import org.robolectric.annotation.Config;
/**
 * Created by herman on 28/12/2016.
 */
@Config(manifest=Config.NONE, sdk = Build.VERSION_CODES.P)
class LogSpec extends ElectricSpecification {

    def "Run Log without error"() {
        when:
            Log.d("TAG", "Hello");
        then:
            notThrown(Throwable)
    }

}