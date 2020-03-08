'''
/*
 * Copyright 2010-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
 '''
from AWSIoTPythonSDK.MQTTLib import AWSIoTMQTTClient
import time, math, json
from grovepi import *

from threading import Thread

# Path to key files
host = "a164yy2at6xf6r.iot.eu-west-1.amazonaws.com"
rootCAPath = "root.pem"
certificatePath = "certificate.pem.crt"
privateKeyPath = "private.pem.key"

# Init AWSIoTMQTTClient
myAWSIoTMQTTClient = AWSIoTMQTTClient("basicPubSub")
myAWSIoTMQTTClient.configureEndpoint(host, 8883)
myAWSIoTMQTTClient.configureCredentials(rootCAPath, privateKeyPath, certificatePath)

# AWSIoTMQTTClient connection configuration
myAWSIoTMQTTClient.configureAutoReconnectBackoffTime(1, 32, 20)
myAWSIoTMQTTClient.configureOfflinePublishQueueing(-1)  # Infinite offline Publish queueing
myAWSIoTMQTTClient.configureDrainingFrequency(2)  # Draining: 2 Hz
myAWSIoTMQTTClient.configureConnectDisconnectTimeout(10)  # 10 sec
myAWSIoTMQTTClient.configureMQTTOperationTimeout(5)  # 5 sec

# Connect and subscribe to AWS IoT
myAWSIoTMQTTClient.connect()

# Initial states of sensors set to false
temp_state = False
hum_state = False
buzzer_state = False
light_state = False
sound_state = False
all_state = False
sleep_time = 1
message = ""

dht_sensor_port = 7 # Connect the DHt sensor to port 7 for temp/humidity sensors
buzzer_pin = 2		# Port for buzzer
blue_led = 3       # Port for green led
red_led = 4         # Port for red led
sound_sensor = 0    # Port for sound sensor

pinMode(buzzer_pin, "OUTPUT")    # Assign mode for buzzer as output
pinMode(blue_led, "OUTPUT")     # Assign mode for green_led as output
pinMode(red_led, "OUTPUT")       # Assign mode for red_led as output


# listener method controls all of the threads as well as acts as the Callback for the MQTT Client to capture the message coming in
# Due to issues around how MQTT Client uses it's callback, the thread arguments have been set to None then re-assigned
# As messages come in from the client, it performs some error handling, checking if it's valid json or not, or if a valid time parameter has been passed in
# If criteria is satisfied, the appropriate sensor is turned on as per the sampling rate or turned off on another thread, while it listens for more messages
# All sensor readings are published to AWS IoT under their appropriate topic, i.e. temperature sensors publishes it's readings to "mqtt_pi/temp"
def listener(client, userdata, aws_content, temp=None, hum=None, buzzer=None, light_on=None, sound=None, all_sensors=None):
    print "listening on topic: mqtt_message"
    global temp_state, hum_state, buzzer_state, light_state, sound_state, all_state, sleep_time, message
    if temp is None or hum is None or buzzer is None or light_on is None or sound is None or all_sensors is None:
        temp = Thread(target=temp_publisher)
        hum = Thread(target=hum_publisher)
        buzzer = Thread(target=buzzer_on)
        light_on = Thread(target=light_show_on)
        sound = Thread(target=sound_on)
        all_sensors = Thread(target=all_sensors_on)

    if client or userdata or aws_content:
        print("Received a new message: ")
        print(aws_content.payload)
        try:
            content = json.loads(aws_content.payload)
            # print content
            message = content.get("message", "")
            time_param = content.get("time", "")
            if time_param == "":
                sleep_time = 2
            else:
                sleep_time = float(time_param)
                if sleep_time < 0:
                    sleep_time = 2
        except ValueError as e:
            print (e, "invalid json")

    if message == "temp_on":
        temp_state = True
        if not temp.is_alive():
            temp = Thread(target=temp_publisher)
            temp.start()
    elif message == "hum_on":
        hum_state = True
        if not hum.is_alive():
            hum = Thread(target=hum_publisher)
            hum.start()
    elif message == "buzzer_on":
        buzzer_state = True
        if not buzzer.is_alive():
            buzzer = Thread(target=buzzer_on)
            buzzer.start()
    elif message == "led_on":
        light_state = True
        if not light_on.is_alive():
            light_on = Thread(target=light_show_on)
            light_on.start()
    elif message == "sound_on":
        sound_state = True
        if not sound.is_alive():
            sound = Thread(target=sound_on)
            sound.start()
    elif message == "all_on":
        all_state = True
        if not all_sensors.is_alive():
            all_sensors = Thread(target=all_sensors_on)
            all_sensors.start()
    elif message == "buzzer_off":
        buzzer_state = False
        digitalWrite(buzzer_pin, 0)
    elif message == "temp_off":
        temp_state = False
        time.sleep(sleep_time)
        digitalWrite(blue_led, 0)
        digitalWrite(red_led, 0)
    elif message == "hum_off":
        hum_state = False
        time.sleep(sleep_time)
        digitalWrite(blue_led, 0)
        digitalWrite(red_led, 0)
    elif message == "led_off":
        light_state = False
        time.sleep(sleep_time)
        digitalWrite(blue_led, 0)
        digitalWrite(red_led, 0)
    elif message == "sound_off":
        sound_state = False
    elif message == "all_off":
        all_state = False
        time.sleep(sleep_time)
        digitalWrite(blue_led, 0)
        digitalWrite(red_led, 0)
        digitalWrite(buzzer_pin, 0)
    else:
        temp_state = False
        hum_state = False
        buzzer_state = False
        light_state = False
        sound_state = False

# This method is responsible for turning all sensors on
def all_sensors_on():
    while all_state:
        try:
            [temp, hum] = dht(dht_sensor_port, 0)  # Get the Temperature from the DHT sensor
            if math.isnan(temp):
                temp = 0
            if math.isnan(hum):
                hum = 0
            sound = analogRead(sound_sensor)
            myAWSIoTMQTTClient.publish("mqtt_pi/temp", temp, 1)
            myAWSIoTMQTTClient.publish("mqtt_pi/hum", hum, 1)
            myAWSIoTMQTTClient.publish("mqtt_pi/sound", sound, 1)
            print "Temp: ", temp
            print "Humidity: ", hum
            print "Sound Level: ", sound
            digitalWrite(buzzer_pin, 1)
            digitalWrite(blue_led, 1)
            print "Blue"
            myAWSIoTMQTTClient.publish("mqtt_pi/led", "Blue", 1)
            myAWSIoTMQTTClient.publish("mqtt_pi/buzzer", "On", 1)
            time.sleep(sleep_time)
            digitalWrite(blue_led, 0)
            digitalWrite(buzzer_pin, 0)
            # myAWSIoTMQTTClient.publish("mqtt_pi/led", "Off", 1)
            myAWSIoTMQTTClient.publish("mqtt_pi/buzzer", "Off", 1)
            # time.sleep(sleep_time)
            digitalWrite(red_led, 1)
            print "Red"
            digitalWrite(buzzer_pin, 1)
            myAWSIoTMQTTClient.publish("mqtt_pi/led", "Red", 1)
            myAWSIoTMQTTClient.publish("mqtt_pi/buzzer", "On", 1)
            time.sleep(sleep_time)
            digitalWrite(red_led, 0)
            digitalWrite(buzzer_pin, 0)
            # myAWSIoTMQTTClient.publish("mqtt_pi/led", "Off", 1)
            myAWSIoTMQTTClient.publish("mqtt_pi/buzzer", "Off", 1)
            time.sleep(sleep_time)
        except KeyboardInterrupt:	# Stop the sensors before stopping
            digitalWrite(buzzer_pin, 0)
            digitalWrite(blue_led, 0)
            digitalWrite(red_led, 0)
            break
        except (IOError, TypeError) as e:
            print(e, "Error")

# This method is responsible for capturing temperature readings as per the sampling rate provided
def temp_publisher():
    while temp_state:
        try:
            [temp, hum] = dht(dht_sensor_port, 0)  # Get the Temperature from the DHT sensor
            if math.isnan(temp):
                temp = 0
            print "Temp: ",temp
            myAWSIoTMQTTClient.publish("mqtt_pi/temp", temp, 1)
            if temp > 20:
                digitalWrite(blue_led, 0)
                digitalWrite(red_led, 1)
                myAWSIoTMQTTClient.publish("mqtt_pi/led", "Red", 1)
            else:
                digitalWrite(red_led, 0)
                digitalWrite(blue_led, 1)
                myAWSIoTMQTTClient.publish("mqtt_pi/led", "Blue", 1)
            time.sleep(sleep_time)
        except (IOError, TypeError) as e:
            print(e, "Error")

# This method is responsible for capturing humidity readings as per the sampling rate provided
def hum_publisher():
    while hum_state:
        try:
            [temp, hum] = dht(dht_sensor_port, 0)  # Get the Humidity from the DHT sensor
            if math.isnan(hum):
                hum = 0
            print "Humidity: ",hum
            myAWSIoTMQTTClient.publish("mqtt_pi/hum", hum, 1)
            if hum > 20:
                digitalWrite(blue_led, 0)
                digitalWrite(red_led, 1)
                myAWSIoTMQTTClient.publish("mqtt_pi/led", "Red", 1)
            else:
                digitalWrite(red_led, 0)
                digitalWrite(blue_led, 1)
                myAWSIoTMQTTClient.publish("mqtt_pi/led", "Blue", 1)
            time.sleep(sleep_time)
        except (IOError, TypeError) as e:
            print(e, "Error")

# This method is responsible for turning the buzzer sensor on/off as per the sampling rate provided
def buzzer_on():
    while buzzer_state:
        try:
            digitalWrite(buzzer_pin, 1)
            print "Buzzer: On"
            myAWSIoTMQTTClient.publish("mqtt_pi/buzzer", "On", 1)
            time.sleep(sleep_time)
            digitalWrite(buzzer_pin, 0)
            print "Buzzer: Off"
            myAWSIoTMQTTClient.publish("mqtt_pi/buzzer", "Off", 1)
            time.sleep(sleep_time)
        except KeyboardInterrupt:	# Stop the buzzer before stopping
            digitalWrite(buzzer_pin, 0)
            break
        except (IOError,TypeError) as e:
            print(e, "Error")

# This method is responsible for flickering the blue/red led's on/off as per the sampling rate provided
def light_show_on():
    while light_state:
        try: # Start flicking lights on and off
            digitalWrite(blue_led, 1)
            print "Blue"
            myAWSIoTMQTTClient.publish("mqtt_pi/led", "Blue", 1)
            time.sleep(sleep_time)
            digitalWrite(blue_led, 0)
            digitalWrite(red_led, 1)
            print "Red"
            myAWSIoTMQTTClient.publish("mqtt_pi/led", "Red", 1)
            time.sleep(sleep_time)
            digitalWrite(red_led, 0)
        except KeyboardInterrupt:	# Stop the led before stopping
            digitalWrite(blue_led, 0)
            digitalWrite(red_led, 0)
            break
        except (IOError, TypeError) as e:
            print(e, "Error")

# This method is responsible for capturing sound level readings as per the sampling rate provided
def sound_on():
    while sound_state:
        try:
            sound = analogRead(sound_sensor)
            print "Sound Level: ", sound
            myAWSIoTMQTTClient.publish("mqtt_pi/sound", sound, 1)
            time.sleep(sleep_time)
        except (IOError,TypeError) as e:
            print(e, "Error")

# Declaration of all threads
temp_thread = Thread(target=temp_publisher).start()
hum_thread = Thread(target=hum_publisher).start()
buzzer_thread = Thread(target=buzzer_on).start()
light_on_thread = Thread(target=light_show_on).start()
sound_thread = Thread(target=sound_on).start()
all_sensors_thread = Thread(target=all_sensors_on).start()
listener_thread = Thread(target=listener, args=(None, None, None, temp_thread, hum_thread, buzzer_thread, light_on_thread, sound_thread, all_sensors_thread,))
listener_thread.start()

# Uses listener as callback function to access in same Thread
myAWSIoTMQTTClient.subscribe("mqtt_message", 1, listener)

while True:
    time.sleep(1)