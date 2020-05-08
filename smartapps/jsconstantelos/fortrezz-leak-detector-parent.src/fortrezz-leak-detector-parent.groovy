/**
 *  Leak Detector for FortrezZ Water Meter
 *
 *  Copyright 2016 Daniel Kurin
 *
 */
definition(
    name: "FortrezZ Leak Detector Parent",
    namespace: "jsconstantelos",
    author: "FortrezZ, LLC",
    description: "Use the FortrezZ Water Meter to identify leaks in your home's water system.",
    category: "Green Living",
    iconUrl: "http://swiftlet.technology/wp-content/uploads/2016/05/logo-square-200-1.png",
    iconX2Url: "http://swiftlet.technology/wp-content/uploads/2016/05/logo-square-500.png",
    iconX3Url: "http://swiftlet.technology/wp-content/uploads/2016/05/logo-square.png")

preferences {
	page(name: "page2", title: "Plugin version 1.5\nSelect device and actions", install: true, uninstall: true)
}

def page2() {
    dynamicPage(name: "page2") {
        section("Choose a water meter to monitor:") {
            input(name: "meter", type: "capability.energyMeter", title: "Water Meter", description: null, required: true, submitOnChange: true)
        }

        if (meter) {
            section {
                app(name: "childRules", appName: "FortrezZ Leak Detector Child", namespace: "jsconstantelos", title: "Create New Leak Detector...", multiple: true)
            }
        }
        
        section("Send notifications through...") {
        	input(name: "pushNotification", type: "bool", title: "SmartThings App", required: false)
        	input(name: "smsNotification", type: "bool", title: "Text Message (SMS)", submitOnChange: true, required: false)
            if (smsNotification)
            {
            	input(name: "phone", type: "phone", title: "Phone number?", required: true)
            }
            input(name: "minutesBetweenNotifications", type: "number", title: "Minutes between notifications", required: true, defaultValue: 60)
        }

		log.debug "there are ${childApps.size()} child smartapps"
        def childRules = []
        childApps.each {child ->
            //log.debug "child ${child.id}: ${child.settings()}"
            childRules << [id: child.id, rules: child.settings()]
        }
        state.rules = childRules
        //log.debug("Child Rules: ${state.rules} w/ length ${state.rules.toString().length()}")
        log.debug "Parent Settings: ${settings}"
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
	state.startTime = 0
	subscribe(meter, "cumulative", cumulativeHandler)
	subscribe(meter, "gpm", gpmHandler)
    log.debug("Subscribing to events")
}

def cumulativeHandler(evt) {
	//Date Stuff
   	def daysOfTheWeek = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
    def today = new Date()
    today.clearTime()
    Calendar c = Calendar.getInstance();
    c.setTime(today);
    int dow = c.get(Calendar.DAY_OF_WEEK);
    def dowName = daysOfTheWeek[dow-1]
    
	def gpm = meter.latestValue("gpm")
    def cumulative = new BigDecimal(evt.value)
    log.debug "Cumulative Handler: [gpm: ${gpm}, cumulative: ${cumulative}]"
    def rules = state.rules
    rules.each { it ->
        def r = it.rules
        def childAppID = it.id
    	//log.debug("Rule: ${r}")
    	switch (r.type) {
            case "Mode":
            	log.debug("Mode Test: ${location.currentMode} in ${r.modes}... ${findIn(r.modes, location.currentMode)}")
                if (findIn(r.modes, location.currentMode))
                {
                	log.debug("Threshold:${r.gpm}, Value:${gpm}")
                	if(gpm > r.gpm)
                    {
                    	sendNotification(childAppID, gpm)
                        if(r.dev)
                        {
                        	//log.debug("Child App: ${childAppID}")
                        	def activityApp = getChildById(childAppID)
                            activityApp.devAction(r.command)
                        }
                    }
                }
                break

            case "Time Period":
            	log.debug("Time Period Test: ${r}")
                def boolTime = timeOfDayIsBetween(r.startTime, r.endTime, new Date(), location.timeZone)
                def boolDay = !r.days || findIn(r.days, dowName) // Truth Table of this mess: http://swiftlet.technology/wp-content/uploads/2016/05/IMG_20160523_150600.jpg
                def boolMode = !r.modes || findIn(r.modes, location.currentMode)
                
            	if(boolTime && boolDay && boolMode)
                {
                    if(gpm > r.gpm)
                    {
                        sendNotification(childAppID, r.gpm)
                        if(r.dev)
                        {
                            def activityApp = getChildById(childAppID)
                            activityApp.devAction(r.command)
                        }
                    }
                }
            	break

            case "Accumulated Flow":
            	log.debug("Accumulated Flow Test: ${r}")
                def boolTime = timeOfDayIsBetween(r.startTime, r.endTime, new Date(), location.timeZone)
                def boolDay = !r.days || findIn(r.days, dowName) // Truth Table of this mess: http://swiftlet.technology/wp-content/uploads/2016/05/IMG_20160523_150600.jpg
                def boolMode = !r.modes || findIn(r.modes, location.currentMode)
                
            	if(boolTime && boolDay && boolMode)
                {
                	def delta = 0
                    if(state["accHistory${childAppID}"] != null)
                    {
                    	delta = cumulative - state["accHistory${childAppID}"]
                    }
                    else
                    {
                    	state["accHistory${childAppID}"] = cumulative
                    }
                	log.debug("Currently in specified time, delta from beginning of time period: ${delta}")
                    
                    if(delta > r.gallons)
                    {
                        sendNotification(childAppID, r.gallons)
                        if(r.dev)
                        {
                            def activityApp = getChildById(childAppID)
                            activityApp.devAction(r.command)
                        }
                    }
                }
                else
                {
                	log.debug("Outside specified time, saving value")
                    state["accHistory${childAppID}"] = cumulative
                }
            	break

            case "Water Valve Status":
            	log.debug("Water Valve Test: ${r}")
            	def child = getChildById(childAppID)
                //log.debug("Water Valve Child App: ${child.id}")
                if(child.isValveStatus(r.valveStatus))
                {
                    if(gpm > r.gpm)
                    {
                        sendNotification(childAppID, gpm)
                   }
                }
                break

            case "Switch Status":
            	break

            default:
                break
        }
    }
}

def gpmHandler(evt) {
	//Date Stuff
   	def daysOfTheWeek = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
    def today = new Date()
    today.clearTime()
    Calendar c = Calendar.getInstance();
    c.setTime(today);
    int dow = c.get(Calendar.DAY_OF_WEEK);
    def dowName = daysOfTheWeek[dow-1]
	def gpm = evt.value
    def cumulative = meter.latestValue("cumulative")
    def rules = state.rules
    rules.each { it ->
        def r = it.rules
        def childAppID = it.id
    	switch (r.type) {

		case "Continuous Flow":
                def boolMode = !r.modes || findIn(r.modes, location.currentMode)
	            if (state.startTime == 0) {
                	log.debug "GPM HANDLER : Start monitoring GPM for continuous flow, so set up important variables..."
                    state.startTime = now()
            	}
                def timeDelta = (now() - state.startTime)/60000
                if (timeDelta > r.flowMinutes && boolMode) {
                	log.debug "GPM HANDLER : Need to send a notification!"
                    sendNotification(childAppID, Math.round(r.flowMinutes))
                    if (r.dev) {
                        def activityApp = getChildById(childAppID)
                        activityApp.devAction(r.command)
                    }
                }
				if (gpm == "0") {
                	log.debug "GPM HANDLER : Flow stopped, so clean up and get ready for another cycle..."
                    state.startTime = 0
                }

			break

            default:
                break
        }
	}	
}
def sendNotification(device, gpm)
{
	def set = getChildById(device).settings()
	def msg = ""
    if(set.type == "Accumulated Flow")
    {
    	msg = "Water Flow Warning: \"${set.ruleName}\" is over gallons exceeded threshold of ${gpm} gallons"
    }
    else if(set.type == "Continuous Flow")
    {
    	msg = "Water Flow Warning: \"${set.ruleName}\" is over the constant flow threshold of ${gpm} minutes"
    }
    else
    {
    	msg = "Water Flow Warning: \"${set.ruleName}\" is over GPM exceeded threshold of ${gpm}gpm"
    }
    log.debug(msg)
    
    // Only send notifications as often as the user specifies
    def lastNotification = 0
    if(state["notificationHistory${device}"])
    {
    	lastNotification = Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", state["notificationHistory${device}"]).getTime()
    }
    def td = now() - lastNotification
    log.debug("Last Notification at ${state["notificationHistory${device}"]}... ${td/(60*1000)} minutes")
    if(td/(60*1000) > minutesBetweenNotifications.value)
    {
    	log.debug("Sending Notification")
        if (pushNotification)
        {
            sendPush(msg)
            state["notificationHistory${device}"] = new Date()
        }
        if (smsNotification)
        {
            sendSms(phone, msg)
            state["notificationHistory${device}"] = new Date()
        }
    }
}

def getChildById(app)
{
	return childApps.find{ it.id == app }
}

def findIn(haystack, needle)
{
	def result = false
	haystack.each { it ->
    	//log.debug("findIn: ${it} <- ${needle}")
    	if (needle == it)
        {
        	//log.debug("Found needle in haystack")
        	result = true
        }
    }
    return result
}