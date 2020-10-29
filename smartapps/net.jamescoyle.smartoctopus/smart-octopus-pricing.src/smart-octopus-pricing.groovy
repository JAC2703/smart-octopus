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
 */

definition(
    name: "Smart Octopus: Pricing",
    namespace: "net.jamescoyle.smartoctopus",
    author: "James Coyle",
    description: "Control devices based on Octopus Agile pricing",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)

preferences {
	section("Electricity region code:") {
		input "elecRegion", title: "Select region", "enum", options: elecRegions(), required: true
	}
	section("When prices plunge, turn these devies on (and off when they, uh, unplunge):") {
		input "plungeSwitches", "capability.switch", Title: "Choose switches", multiple: true, required: false
	}
    section("Notify on plunge?:") {
		input "notifyPlunge", title: "Yes/ No?", "enum", options: ["Yes": "Yes", "No": "No"], defaultValue: "No", required: true
	}
}

def elecRegions() {
    // If anyone knows how to get the value of an enum, and not it's index... call me. Otherwise we're doing this. 
    return ["A": "A", "B": "B", "C": "C", "D": "D", "E": "E", "F": "F", "G": "G", "H": "H", "I": "I", "J": "J", "K": "K", "L": "L", "M": "M"]
}

def installed() {
	log.debug "Installed with settings: ${settings}"
    
    initialize()
}


def updated() {
	log.debug "Updated with settings: ${settings}"
    
    initialize()
}

def initialize() {
    setStates()
    
    getPricesFirstTime()
    checkPricesFirstTime()
    
	schedule("30 50 * * * ?", getPricesSchedule)
    schedule("0 */30 * * * ?", checkPricesSchedule)
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
    
    processGetPrices()

}

def processGetPrices() {
    def prices = getPricesFromAPI()
    
    if(prices == []) {
        log.error "No prices returned from API. See previous errors."
        state.agilePrices = []
    }
    else{
        log.debug "Fetched new pricing data"
        state.agilePrices = prices.results
    }
    
}

def getPricesFromAPI() {
    def dateFrom = new Date().format("yyyy-MM-dd'T'HH:'00Z'", TimeZone.getTimeZone('UTC'))
    log.debug "Getting prices from API from ${dateFrom}"
    
    def url = "https://api.octopus.energy/v1/products/AGILE-18-02-21/electricity-tariffs/E-1R-AGILE-18-02-21-${settings.elecRegion}/standard-unit-rates/?period_from=${dateFrom}"
    
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
    
    if(state.agilePrices.size() < 14) {
      checkPrices()
    }
    else{
        log.debug "${state.agilePrices.size()} prices are already in cache"
    }
}

def checkPrices() {
    log.debug "Checking current known prices for action"
    
    Date date = new Date()
    
    Iterator i = state.agilePrices.iterator();
    while (i.hasNext()) {
        price = i.next()
        def fromDate = Date.parse("yyyy-MM-dd'T'HH:mm:ssz", price.valid_from.replaceAll('Z', '+0000'))
        def toDate = Date.parse("yyyy-MM-dd'T'HH:mm:ssz", price.valid_to.replaceAll('Z', '+0000'))
        def currentDate = new Date()
        
        //log.debug "Checking ${currentDate} is between ${price.valid_from}...${fromDate} and ${price.valid_to}...${toDate}"
        
        if(currentDate > toDate){
            i.remove()
        }
        else if(currentDate >= fromDate && currentDate < toDate) {
            log.info "Current Agile price is ${price.value_inc_vat}"
            
            if(state.agileCurrentPrice != price.value_inc_vat) {
                // There has been a price change!
                state.agileCurrentPrice = price.value_inc_vat
            }
            
            // Negative price? 
            if(price.value_inc_vat < 0) {
                // Plunge! 
              state.lastPlungeFromDate = fromDate
              state.lastPlungeToDate = toDate
                
                debug.log "Plunge! ${price.value_inc_vat}"
                
                turnOnPlungeDevices()
                
                sendPlungeNotification(price.value_inc_vat);
            }
            else{
                // not in plunge
                if(state.lastPlungeTurnedOff == false) {
                    log.info "Turning off devices from plunge timeframe ${state.lastPlungeFromDate} to ${state.lastPlungeToDate}"
                    turnOffPlungeDevices()
                }
            }
        }
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

def turnOnPlungeDevices() {
	plungeSwitches.each {plungeSwitch -> 
        log.debug "Turning on ${plungeSwitch}"
        plungeSwitch.on()
    }
    
    state.lastPlungeTurnedOff = false
}

def turnOffPlungeDevices() {
	plungeSwitches.each { plungeSwitch -> 
        log.debug "Turning off ${plungeSwitch}"
        plungeSwitch.off()
    }
    
    state.lastPlungeTurnedOff = true
}

def sendPlungeNotification(price){
    sendNotification("Agile pricing is below £0.00 @ ${price}", [method: "push"])
}