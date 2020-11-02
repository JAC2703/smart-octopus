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
 *
 *  2020-10-28    Implemented ability to get pricing data with basic app framework 
 *  2020-10-29    3 levels of price categories. Plunge (<= 0.00), Cheap (user set pricing) and Regular (user set pricing)
 *  2020-10-31    Switches will be ignored from automation if they have been manually switched outside of automation. ST doesn't provide a way of capturing if the app or user makes the request
                  so it's implemented as a work around by adding the device to a list when the app triggers an event. The event handle then removes from the list and knows the event isn't user generated.
                  If a switch is manually turned on, it will not be turned off automatically. If the switch is then manually turned back off it will then be included in future automations.
    2020-11-01    Added 4th level of switches. Renamed to low, medium, high and plunge pricing. 
                  Added optional virtual devices for each pricing level
 */

definition(
    name: "Smart Octopus Pricing",
    namespace: "jamescoyle",
    author: "James Coyle",
    description: "Control devices based on Octopus Agile pricing",
    category: "My Apps",
    iconUrl: "https://www.jamescoyle.net/wp-content/uploads/2020/10/octo-logo-fav.png",
    iconX2Url: "https://www.jamescoyle.net/wp-content/uploads/2020/10/octo-logo.png",
    iconX3Url: "https://www.jamescoyle.net/wp-content/uploads/2020/10/octo-logo.png"
)

preferences {
    section("Smart Octopus Pricing") {
        paragraph "Configure which devices to turn on and off based on the below thresholds. The device will be turned on if the price is at or below the specified value and turned off when the price rises above it."
    }
	section("Electricity region code:") {
		input "elecRegion", title: "Select region", "enum", options: elecRegions(), required: true
	}
	section("Plunge Pricing") {
		input "plungeSwitches", "capability.switch", title: "Turn these devices on", multiple: true, required: false
        input "notifyPlunge", title: "Yes/ No?", "enum", options: ["Yes": "Yes", "No": "No"], defaultValue: "No", required: true
	}
    section("Low Pricing") {
        input "cheapValue", "number", title: "When price is less than (x.xxp)", defaultValue: 5, required: true
		input "cheapSwitches", "capability.switch", title: "Turn these devices on", multiple: true, required: false
	}
    section("Medium Pricing") {
        input "regularValue", "number", title: "When price is less than (x.xxp)", defaultValue: 10, required: true
		input "regularSwitches", "capability.switch", title: "Turn these devices on", multiple: true, required: false
	}
    section("High Pricing") {
        input "highValue", "number", title: "When price is less than (x.xxp)", defaultValue: 15, required: true
		input "highSwitches", "capability.switch", title: "Turn these devices on", multiple: true, required: false
	}
    section("Add virtual devices for each pricing level?") {
        paragraph "Virtual devices will be triggered by the above thresholds and can be used in Automations."
        input "addVirtualDevices", title: "Yes/ No?", "enum", options: ["Yes": "Yes", "No": "No"], defaultValue: "No", required: true
	}
    section("Notes") {
        paragraph "If you manually control a device that is chosen for automation (e.g. turn it on using the switch on the device) it will no longer be controlled by this app. To enable the app control again, manually set the device back to it's original state."
    }
}

def getApiBase() { return "https://api.octopus.energy" }
def getApiPricingCall() { return "${getApiBase()}/v1/products/AGILE-18-02-21/electricity-tariffs/E-1R-AGILE-18-02-21-${settings.elecRegion}/standard-unit-rates/" }
def getChildDeviceId(n) { return "octopus-agile-pricing-" + n }
def getChildDeviceName(n) { return "Octopus Agile Pricing " + n }
def getChildDeviceTypeName() { return "octopus-agile-pricing" }

def elecRegions() {
    // If anyone knows how to get the value of an enum, and not it's index... call me. Otherwise we're doing this. 
    return ["A": "A", "B": "B", "C": "C", "D": "D", "E": "E", "F": "F", "G": "G", "H": "H", "I": "I", "J": "J", "K": "K", "L": "L", "M": "M"]
}

def getAllSwitches(){
    def switches = []
    
    if(plungeSwitches != null) {
        switches.addAll(plungeSwitches)
    }
    
    if(cheapSwitches != null) {
        switches.addAll(cheapSwitches)
    }
    
    if(regularSwitches != null) {
        switches.addAll(regularSwitches)
    }
    
    if(highSwitches != null) {
        switches.addAll(highSwitches)
    }
    
    if(addVirtualDevices == "Yes") {
        def existingChildDevices = getChildDevices()
    	switches.addAll(existingChildDevices)
    }
    
    return switches
}   

def installed() {
	log.debug "Installed with settings: ${settings}"
    
    initialize()
    
    // randomise API call minute
    def randMinute = Math.abs(new Random().nextInt() % 59) + 1
    log.debug "Scheduling API call for '30 ${randMinute} * * * ?'"
    
    schedule("30 ${randMinute} * * * ?", getPricesSchedule)
    schedule("0 */30 * * * ?", checkPricesSchedule)
}

def uninstalled() {
    removeChildDevices()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

    initialize()
}

def initialize() {
    unsubscribe()
    setStates()
    
    createChildDevices()
    
    setSwitchRates()    
    subscribeToSwitches()
    
    getPricesFirstTime()
    checkPricesFirstTime()
}

def setStates() {
    setStateValue('agilePrices', [])
    setStateValue('deviceIgnoreList', [])
    setStateValue('deviceIgnoreEventList', [:])
    setStateValue('agileCurrentPrice', 99)
    setStateValue('lastPlungeTurnedOff', false) // safety first and second
    setStateValue('lastPlungeFromDate', 'Unknown')
    setStateValue('lastPlungeToDate', 'Unknown')
}

def setStateValue(s, v) {
    if(!state.containsKey(s)){
        state[s] = v
    }
}

def setSwitchRates() {
    def switches = [:]
    
    if(plungeSwitches != null) {
        plungeSwitches.each { s ->
            switches.put(s.getId(), 0)
        }
    }
    
    if(cheapSwitches != null) {
        cheapSwitches.each { s ->
            switches.put(s.getId(), cheapValue)
        }
    }
    
    if(regularSwitches != null) {
        regularSwitches.each { s ->
            switches.put(s.getId(), regularValue)
        }
    }
    
    if(highSwitches != null) {
        highSwitches.each { s ->
            switches.put(s.getId(), highValue)
        }
    }
    
    if(addVirtualDevices == "Yes") {
        def existingChildDevices = getChildDevices()
        existingChildDevices.each { c ->
            switch (c.deviceNetworkId) {
                case getChildDeviceId("plunge"):
                    switches.put(c.id, 0)
                    break
                    
                case getChildDeviceId("low"):
                    switches.put(c.id, cheapValue)
                    break
                    
                case getChildDeviceId("medium"):
                    switches.put(c.id, regularValue)
                    break
                    
                case getChildDeviceId("high"):
                    switches.put(c.id, highValue)
                    break
            }
        }
    }
    
    state.switches = switches
    
    log.info "${switches.size()} switches added for automation"
}

def createChildDevices() {
    def existingChildDevices = getChildDevices()

    if(addVirtualDevices == "Yes") {
        if(existingChildDevices.size() > 0) {
            log.debug "Child devices already exist"
        }
        else {
            log.info "Creating child virtual devices"

            createChildDevice("plunge", "Plunge")
            createChildDevice("low", "Low")
            createChildDevice("medium", "Medium")
            createChildDevice("high", "High")
        }
    }
    else{
        if(existingChildDevices.size() > 0) {
            log.debug "Removing child devices"
        	removeChildDevices()
        }
    }
}

def createChildDevice(appendId, appendName) {
    def id = getChildDeviceId(appendId)
    def name = getChildDeviceName(appendName)
    
    def priceDevice =  addChildDevice(app.namespace, getChildDeviceTypeName(), id, location.hubs[0].id, ["name": name, "label": name, "completedSetup": true])
}

def removeChildDevices() {
    getAllChildDevices().each { d ->
        deleteChildDevice(d.deviceNetworkId)
    }
}

/* Subscriptions */
def subscribeToSwitches(){
    def switches = getAllSwitches()
    switches.each { s -> 
        subscribe(s, "switch", manualSwitchHandler)
    }    
    
    log.debug "Subscribed to ${switches.size()} devices"
}

def manualSwitchHandler(evt) {
    def device = findDevice(evt.deviceId)
    
    def automationTrigger = state.deviceIgnoreEventList.remove(device.id)
    if(automationTrigger == null) {
        // If device isn't in the list then it's controlled with automation. 
        def deviceInIgnoreList = state.deviceIgnoreList.remove(evt.deviceId)
        if(deviceInIgnoreList == false){
            log.info "Manual event detected for '${device}' and will be ignored in automation"
            state.deviceIgnoreList.add(evt.deviceId)
        }
        else{
            // No need to remove deviceId from list as it's already removed as part of the check
            log.info "Manual event detected for '${device}' and will be included in automation"
        }
    }
}

/* Get prices and store in state */
def getPricesFirstTime() {
    if(state.agilePrices.size() == 0){
      log.debug "Getting price data for the first time"
    
      processGetPrices()
    }
    else{
        log.debug "Skipping getting price data from API"
    }

}

def getPricesSchedule() {
    log.debug "Getting price data from API"

    if(state.agilePrices.size() < 14) {
        processGetPrices()
    }
    else{
        log.debug "${state.agilePrices.size()} price data items are already in cache"
    }
}

def processGetPrices() {
    def prices = getPricesFromAPI()
    
    if(prices == []) {
        log.error "No price data returned from API. See previous errors."
        state.agilePrices = []
    }
    else{
        log.debug "Fetched ${prices.results.size()} new pricing intervals"
        state.agilePrices = prices.results
    }

}

def getPricesFromAPI() {
    def dateFrom = new Date().format("yyyy-MM-dd'T'HH:'00Z'", TimeZone.getTimeZone('UTC'))
    log.debug "Getting price data from API from ${dateFrom}"

    def url = "${getApiPricingCall()}?period_from=${dateFrom}"

    def resp = requestGET(url)
    
    if(resp.status == 200) {
        return resp.data
    } 
    else {
        log.error "Error calling API for prices. response code: ${resp.status}, data: ${resp.data}"
        return []
    }
}


/* check known prices and do stuff */
def checkPricesFirstTime() {
    log.debug "Checking prices for the first time"

    if(state.switches.size() > 0) {
        checkPrices()
    }
}

def checkPricesSchedule() {
    log.debug "Checking prices"

    if(state.switches.size() > 0) {
        checkPrices()
    }
}

def checkPrices() {
    log.debug "Checking current known prices for action"
    
    Date date = new Date()
    
    def removePrices = []
    state.agilePrices.each { price ->
        def fromDate = Date.parse("yyyy-MM-dd'T'HH:mm:ssz", price.valid_from.replaceAll('Z', '+0000'))
        def toDate = Date.parse("yyyy-MM-dd'T'HH:mm:ssz", price.valid_to.replaceAll('Z', '+0000'))
        def currentDate = new Date()
        
        //log.debug "Checking ${currentDate} is between ${price.valid_from}...${fromDate} and ${price.valid_to}...${toDate}"
        
        if(currentDate > toDate){
            removePrices.add(price)
        }
        else if(currentDate >= fromDate && currentDate < toDate) {
            log.info "Current Agile price is ${price.value_inc_vat}"
            
            if(state.agileCurrentPrice != price.value_inc_vat) {
                // There has been a price change!
                state.agileCurrentPrice = price.value_inc_vat
            }
            
            def turnOn = []
            def turnOff = []
            
            state.switches.each { s -> 
                def thresholdPrice = s.value
                def device = findDevice(s.key)
                if(device == null) {
                    log.warn "Device ${s.key} has been deleted. Please refresh the app"
                    //runOnce(new Date(), initialize)
                }
                else if(thresholdPrice >= state.agileCurrentPrice){
                    log.debug "Setting device ${s.key}:${device} ON as price ${state.agileCurrentPrice} is below threshold ${thresholdPrice}"
                    turnOn.add(device)
                }
                else{
                    log.debug "Setting device ${s.key}:${device} OFF as price ${state.agileCurrentPrice} is above threshold ${thresholdPrice}"
                    turnOff.add(device)
                }
            }
            
            log.info "Ensuring ${turnOn.size()} device(s) are ON and ${turnOff.size()} device(s) are OFF at price ${state.agileCurrentPrice}"
            
            turnOffDevices(turnOff)
            turnOnDevices(turnOn)
            
            // Negative price? 
            if(price.value_inc_vat < 0) {
                log.info "Alerting plunge pricing!"
                sendPlungeNotification(price.value_inc_vat);
            }
        }
    }
    
    if(removePrices.size() > 0) {
        state.agilePrices.removeAll(removePrices)
    }
}

def requestGET(path) {
	headers: ['Accept': 'application/json, text/plain, */*',
              'Content-Type': 'application/json;charset=UTF-8',
              'User-Agent': 'SmartThings']
    try{
        log.debug "Sending GET to ${path}"
        httpGet(uri: path, contentType: 'application/json', headers: headers) {response ->
			return response
        }
    }
    catch (groovyx.net.http.HttpResponseException e) {
		log.warn e.response
        return e.response
	}
}

def findDevice(id) {
    // Not ideal, using join to return new array list of all switches
    def switches = getAllSwitches()
    def p = switches.find{ it.id == id }
    if(p != null) {
        return p
    }
}

def turnOnDevices(turnOn) {
    log.debug "Ensuring ${turnOn.size()} device(s) are ON"
	turnOn.each {s -> 
        if(state.deviceIgnoreList.contains(s.id)) {
            log.info "Device ${s} has been excluded from automation by manually using the device"
        }
        else {
            if(s.latestValue("switch") == null || !s.latestValue("switch").contains('on')) {
                log.debug "Turning ON ${s} from ${s.latestValue("switch")}"
                state.deviceIgnoreEventList.put(s.id, "ON")
                s.on()
            }
        }
    }
}

def turnOffDevices(turnOff) {
    log.debug "Ensuring ${turnOff.size()} device(s) are OFF"
	turnOff.each { s -> 
        if(state.deviceIgnoreList.contains(s.id)) {
            log.info "Device ${s} has been excluded from automation by manually using the device"
        }
        else {
            if(s.latestValue("switch") == null || !s.latestValue("switch").contains('off')) {
                log.debug "Turning OFF ${s} from ${s.latestValue("switch")}"
                state.deviceIgnoreEventList.put(s.id, "OFF")
                s.off()
            }
        }
    }
}

def sendPlungeNotification(price){
    if(notifyPlunge == "Yes") {
    	sendNotification("Agile pricing is below £0.00 @ ${price}", [method: "push"])
    }
}