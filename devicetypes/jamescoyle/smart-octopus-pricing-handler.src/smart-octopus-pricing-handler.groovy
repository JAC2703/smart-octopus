/**
 *  Copyright 2015 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Smart Octopus Pricing
 *
 *  Author: James Coyle
 *  Date: 2020-10-29
 *
 *  2020-10-29 Smartthings does not support custom tiles currently, development on displaying price is paused.
 */

metadata {
	definition(name: "Octopus Agile Pricing", namespace: "jamescoyle", author: "James Coyle", vid: "generic-switch") {
	}
}

def installed() {
    log.info "INSTALLED"
}

def update(price) {

}