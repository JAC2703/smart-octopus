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
 *  Date: 2020-11-02    Basic virtual device to be used for representing price levels (on = price within threshold)
 * 
 */

metadata {
    definition (name: "octopus-agile-pricing", namespace: "jamescoyle", author: "James Coyle") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
    }
    
	tiles(scale: 2) {
        standardTile("switch", "device.switch", width: 2, height: 2, canChangeIcon: true) {
			state "off", label: 'Off', action: "switch.on", icon: "st.switches.switch.off", backgroundColor: "#ffffff", defaultState: true
			state "on", label: 'On', action: "switch.off", icon: "st.switches.switch.on", backgroundColor: "#00A0DC"
		}

		main "switch"
		details "switch"
	}
}

def parse(description) {
}

def on() {
    sendEvent(name: "switch", value: "on")
}

def off() {
    sendEvent(name: "switch", value: "off")
}

def installed() {
    //log.debug "Installed"
}