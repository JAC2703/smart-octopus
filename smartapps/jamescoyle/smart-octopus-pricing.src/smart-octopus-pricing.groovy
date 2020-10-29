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
 *  Date: 2020-10-28
 *
 *  2020-10-28 Implemented ability to get pricing data with basic app framework 
 *  2020-10-29 3 levels of price categories. Plunge (<= 0.00), Cheap (user set pricing) and Regular (user set pricing)
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
    section("Cheap Pricing") {
        input "cheapValue", "number", title: "When price is less than (x.xxp)", defaultValue: 5, required: true
		input "cheapSwitches", "capability.switch", title: "Turn these devices on", multiple: true, required: false
	}
    section("Regular Pricing") {
        input "regularValue", "number", title: "When price is less than (x.xxp)", defaultValue: 12, required: true
		input "regularSwitches", "capability.switch", title: "Turn these devices on", multiple: true, required: false
	}
}

def getApiBase() { return "https://api.octopus.energy" }
def getApiPricingCall() { return "${getApiBase()}/v1/products/AGILE-18-02-21/electricity-tariffs/E-1R-AGILE-18-02-21-${settings.elecRegion}/standard-unit-rates/" }
def getDeviceIdPricing() { return "octo-agile-price-device" }

def elecRegions() {
    // If anyone knows how to get the value of an enum, and not it's index... call me. Otherwise we're doing this. 
    return ["A": "A", "B": "B", "C": "C", "D": "D", "E": "E", "F": "F", "G": "G", "H": "H", "I": "I", "J": "J", "K": "K", "L": "L", "M": "M"]
}

def installed() {
	log.debug "Installed with settings: ${settings}"
    
    initialize()
    
    schedule("30 50 * * * ?", getPricesSchedule)
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
    setStates()
    setSwitchRates()
    createChildDevices()
    
    getPricesFirstTime()
    checkPricesFirstTime()
}

def setStates() {
    setStateValue('agilePrices', [])
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
    
    if (regularSwitches != null) {
        regularSwitches.each { s ->
            switches.put(s.getId(), regularValue)
        }
    }

    state.switches = switches
    
    log.info "${switches.size()} switches added"
}

def createChildDevices() {
    def existingChildDevices = getChildDevices()
    
    if(existingChildDevices.size() > 0) {
        removeChildDevices()
    }
    
    //def priceDevice =  addChildDevice(app.namespace, "Octopus Agile Pricing", getDeviceIdPricing(), null, ["name": "Octopus Agile Pricing", "label": "Octopus Agile Pricing", "completedSetup": true])
    //log.info "Created child device handler for price {$priceDevice}"

}

private removeChildDevices() {
    getAllChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

/* Get prices and store in state */
def getPricesFirstTime() {

    if(state.agilePrices.size() == 0){
      log.debug "Getting prices for the first time"
    
      processGetPrices()
    }
    else{
        log.debug "Skipping getting prices"
    }

}

def getPricesSchedule() {
    log.debug "Getting prices from API"

    if(state.agilePrices.size() < 14) {
        processGetPrices()
    }
    else{
        log.debug "${state.agilePrices.size()} prices are already in cache"
    }
}

def processGetPrices() {
    def prices = getPricesFromAPI()
    
    if(prices == []) {
        log.error "No prices returned from API. See previous errors."
        state.agilePrices = []
    }
    else{
        log.debug "Fetched ${prices.results.size()} new pricing intervals"
        state.agilePrices = prices.results
    }

}

def getPricesFromAPI() {
    def dateFrom = new Date().format("yyyy-MM-dd'T'HH:'00Z'", TimeZone.getTimeZone('UTC'))
    log.debug "Getting prices from API from ${dateFrom}"

    def url = "${getApiPricingCall()}?period_from=${dateFrom}"

    def resp = requestGET(url)
    
    if (resp.status == 200) {
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
    
    checkPrices()
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
                if(thresholdPrice >= state.agileCurrentPrice){
                    log.debug "Setting device ${device} ON as price ${state.agileCurrentPrice} is below threshold ${thresholdPrice}"
                    turnOn.add(device)
                }
                else{
                    log.debug "Setting device ${device} OFF as price ${state.agileCurrentPrice} is below threshold ${thresholdPrice}"
                    turnOff.add(device)
                }
            }
            
            log.info "Ensuring ${turnOn.size()} devices are ON and ${turnOff.size()} devices are OFF at price ${state.agileCurrentPrice}"
            
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

/* TODO This is clunky and should be refactored. 
 * It seems there isn't a way to look up a device from a single 'master' list (unless joining)
 */ 
def findDevice(id) {
    if(plungeSwitches != null) {
        def p = plungeSwitches.find{ it.id == id }
        if(p != null) {
            return p
        }
    }
    
    if (cheapSwitches != null) {
        def c = cheapSwitches.find{ it.id == id }
        if(c != null) {
            return c
        }
    }
    
    if (regularSwitches != null) {
        def r = regularSwitches.find{ it.id == id }
        if(r != null) {
            return r
        }
    }
}

def turnOnDevices(turnOn) {
    log.debug "Turning ON ${turnOn.size()} devices"
	turnOn.each {s -> 
        log.debug "Turning ON ${s}"
        s.on()
    }
}

def turnOffDevices(turnOff) {
    log.debug "Turning OFF ${turnOff.size()} devices"
	turnOff.each { s -> 
        log.debug "Turning OFF ${s}"
        s.off()
    }
}

def sendPlungeNotification(price){
    sendNotification("Agile pricing is below £0.00 @ ${price}", [method: "push"])
}