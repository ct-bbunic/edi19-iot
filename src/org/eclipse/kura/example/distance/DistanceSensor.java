package org.eclipse.kura.example.distance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.cloudconnection.listener.CloudConnectionListener;
import org.eclipse.kura.cloudconnection.listener.CloudDeliveryListener;
import org.eclipse.kura.cloudconnection.message.KuraMessage;
import org.eclipse.kura.cloudconnection.publisher.CloudPublisher;
import org.eclipse.kura.configuration.ConfigurableComponent;
import org.eclipse.kura.gpio.GPIOService;
import org.eclipse.kura.gpio.KuraClosedDeviceException;
import org.eclipse.kura.gpio.KuraGPIODirection;
import org.eclipse.kura.gpio.KuraGPIOMode;
import org.eclipse.kura.gpio.KuraGPIOPin;
import org.eclipse.kura.gpio.KuraGPIOTrigger;
import org.eclipse.kura.gpio.KuraUnavailableDeviceException;
import org.eclipse.kura.message.KuraPayload;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistanceSensor implements ConfigurableComponent, CloudConnectionListener, CloudDeliveryListener {

	private static final Logger logger = LoggerFactory.getLogger(DistanceSensor.class);
	private static final String APP_ID = "DistanceSensor";

	private Map<String, Object> properties;
	private Map<String, Double> distanceSensors = new HashMap<String, Double>();

	// Property Names
	private static final String PROP_NAME_GPIO_PINS = "gpio.pins";
	private static final String PROP_NAME_GPIO_DIRECTIONS = "gpio.directions";
	private static final String PROP_NAME_GPIO_MODES = "gpio.modes";
	private static final String PROP_NAME_GPIO_TRIGGERS = "gpio.triggers";
	private static final String PROP_NAME_RATE = "rate";
	private static final String PROP_NAME_LIMIT = "containerLimits";
	private static final String PROP_NAME_TURNON = "turnOn";

	private GPIOService gpioService;
	private CloudPublisher cloudPublisher;

	private ArrayList<KuraGPIOPin> openPins = new ArrayList<>();

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> executor = null;

	private long startTime;
	private long endTime;
	private double distance;
	private long duration;

	// container measurement properties
	private int fullContainerDistance;
	private int emptyContainerDistance;

	private long rate;
	private boolean turnOn;

	/*
	 * 
	 * Activation methods
	 * 
	 */
	public void setCloudPublisher(CloudPublisher cloudPublisher) {
		this.cloudPublisher = cloudPublisher;
		this.cloudPublisher.registerCloudConnectionListener(DistanceSensor.this);
		this.cloudPublisher.registerCloudDeliveryListener(DistanceSensor.this);
	}

	public void unsetCloudPublisher(CloudPublisher cloudPublisher) {
		this.cloudPublisher.unregisterCloudConnectionListener(DistanceSensor.this);
		this.cloudPublisher.unregisterCloudDeliveryListener(DistanceSensor.this);
		this.cloudPublisher = null;
	}

	public void setGPIOService(GPIOService gpioService) {
		this.gpioService = gpioService;
	}

	public void unsetGPIOService(GPIOService gpioService) {
		this.gpioService = null;
	}

	protected void activate(ComponentContext componentContext, Map<String, Object> properties) {
		logger.info("Bundle {} has started !", APP_ID);
	}

	protected void deactivate(ComponentContext componentContext) {
		closePins();
		if (this.executor != null) {
			this.executor.cancel(true);
		}
		logger.info("Bundle {} has stopped!", APP_ID);
	}

	public void updated(Map<String, Object> properties) {
		logger.info("Updating ... {}", APP_ID);
		doUpdate(properties);

	}

	/*
	 * 
	 * 
	 * Private methods
	 * 
	 */

	private void doUpdate(Map<String, Object> properties) {

		// Close pins, executor and update properties
		closePins();
		if (this.executor != null) {
			this.executor.cancel(true);
		}
		this.properties = properties;
		// Update workflow properties
		Long[] rateArr = (Long[]) properties.get(PROP_NAME_RATE);
		Integer[] limitArr = (Integer[]) properties.get(PROP_NAME_LIMIT);
		this.rate = rateArr[0];
		this.fullContainerDistance = limitArr[0];
		this.emptyContainerDistance = limitArr[1];
		this.turnOn = (Boolean) properties.get(PROP_NAME_TURNON);

		if (turnOn) {
			availablePins();
			getPins();
			calculateDistance();
		}

	}

	private void availablePins() {

		if (this.gpioService != null) {
			logger.info("______________________________");
			logger.info("Available GPIOs on the system:");
			Map<Integer, String> gpios = this.gpioService.getAvailablePins();
			for (Entry<Integer, String> e : gpios.entrySet()) {
				logger.info("#{} - [{}]", e.getKey(), e.getValue());
			}
			logger.info("______________________________");

		}

	}

	private void getPins() {

		// Get values from properties that are configured in Kura UI
		Integer[] pins = (Integer[]) this.properties.get(PROP_NAME_GPIO_PINS);
		Integer[] directions = (Integer[]) this.properties.get(PROP_NAME_GPIO_DIRECTIONS);
		Integer[] modes = (Integer[]) this.properties.get(PROP_NAME_GPIO_MODES);
		Integer[] triggers = (Integer[]) this.properties.get(PROP_NAME_GPIO_TRIGGERS);

		for (int i = 0; i < pins.length; i++) {
			try {
				logger.info("Acquiring GPIO pin {} with params:", pins[i]);
				logger.info("   Direction....: {}", directions[i]);
				logger.info("   Mode.........: {}", modes[i]);
				logger.info("   Trigger......: {}", triggers[i]);
				KuraGPIOPin p = this.gpioService.getPinByTerminal(pins[i], getPinDirection(directions[i]),
						getPinMode(modes[i]), getPinTrigger(triggers[i]));
				p.open();
				openPins.add(p);
				logger.info("GPIO pin {} acquired", pins[i]);

			} catch (IOException e) {
				logger.error("I/O Error occurred!", e);
			} catch (Exception e) {
				logger.error("got errror", e);
			}

		}
	}

	private void calculateDistance() {

		this.executor = this.scheduler.scheduleAtFixedRate(new Runnable() {

			KuraGPIOPin pinEcho = null;
			KuraGPIOPin pinTrigger = null;

			@Override
			public void run() {

				for (int i = 0; i < openPins.size(); i++) {

					if (i % 2 == 0) {
						pinEcho = openPins.get(i);
						pinTrigger = openPins.get(i + 1);

						KuraPayload payload = new KuraPayload();
						try {

							pinTrigger.setValue(true);
							Thread.sleep(0, 1000);
							pinTrigger.setValue(false);

							while (pinEcho.getValue() == false) {
								startTime = System.nanoTime();
							}

							while (pinEcho.getValue() == true) {
								endTime = System.nanoTime();
							}

							duration = endTime - startTime;
							distance = Math.ceil(duration / 1000000000.0 * 17150);
														
							switch (i) {
							case 0:
								distanceSensors.put("left", distance);
								break;
							case 2:
								distanceSensors.put("right", distance);
								break;
							}

							for (String key : distanceSensors.keySet()) {
									
								logger.info("KEY {}, VALUE {}", key, distanceSensors.get(key));
								
								if (distanceSensors.get(key) >= emptyContainerDistance) {
									logger.info("{} PART IS EMPTY", key.toUpperCase());
									payload.addMetric(key, "empty");
									logger.info(String.valueOf(distanceSensors.get(key)));
								} else if ((distanceSensors.get(key) < emptyContainerDistance)
										&& (distanceSensors.get(key) > fullContainerDistance)) {
									logger.info("{} PART IS FILLING", key.toUpperCase());
									payload.addMetric(key, "filling");
									logger.info(String.valueOf(distanceSensors.get(key)));

								} else if (distanceSensors.get(key) <= fullContainerDistance) {
									logger.info("{} PART IS FULL", key.toUpperCase());
									payload.addMetric(key, "full");
									logger.info(String.valueOf(distanceSensors.get(key)));

								}

							}

							if (!payload.metricNames().isEmpty()) {

								KuraMessage message = new KuraMessage(payload);

								try {
									cloudPublisher.publish(message);
								} catch (Exception e) {
									logger.error("Cannot publish message: {}", message, e);
								}

							}

						} catch (KuraUnavailableDeviceException | KuraClosedDeviceException | IOException
								| InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}
		}, 0, this.rate, TimeUnit.SECONDS);
	}

	private void closePins() {

		Iterator<KuraGPIOPin> pins_it = openPins.iterator();
		while (pins_it.hasNext()) {
			try {
				KuraGPIOPin p = pins_it.next();
				logger.warn("Closing GPIO pin {}", p);
				p.close();
			} catch (IOException e) {
				logger.warn("Cannot close pin!");
			}
		}

		openPins.clear();

	}

	private KuraGPIODirection getPinDirection(int direction) {
		switch (direction) {
		case 0:
		case 2:
			return KuraGPIODirection.INPUT;
		case 1:
		case 3:
			return KuraGPIODirection.OUTPUT;
		default:
			return KuraGPIODirection.OUTPUT;
		}
	}

	private KuraGPIOMode getPinMode(int mode) {
		switch (mode) {
		case 2:
			return KuraGPIOMode.INPUT_PULL_DOWN;
		case 1:
			return KuraGPIOMode.INPUT_PULL_UP;
		case 8:
			return KuraGPIOMode.OUTPUT_OPEN_DRAIN;
		case 4:
			return KuraGPIOMode.OUTPUT_PUSH_PULL;
		default:
			return KuraGPIOMode.OUTPUT_OPEN_DRAIN;
		}
	}

	private KuraGPIOTrigger getPinTrigger(int trigger) {
		switch (trigger) {
		case 0:
			return KuraGPIOTrigger.NONE;
		case 2:
			return KuraGPIOTrigger.RAISING_EDGE;
		case 3:
			return KuraGPIOTrigger.BOTH_EDGES;
		case 1:
			return KuraGPIOTrigger.FALLING_EDGE;
		default:
			return KuraGPIOTrigger.NONE;
		}
	}

	@Override
	public void onMessageConfirmed(String arg0) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onConnectionEstablished() {
		// TODO Auto-generated method stub

	}

	@Override
	public void onConnectionLost() {
		// TODO Auto-generated method stub

	}

	@Override
	public void onDisconnected() {
		// TODO Auto-generated method stub

	}

}
