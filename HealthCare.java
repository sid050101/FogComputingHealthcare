package org.fog.test.perfeval;
import org.cloudbus.cloudsim.Host;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.*;
import org.fog.mobilitydata.DataParser;
import org.fog.mobilitydata.RandomMobilityGenerator;
import org.fog.mobilitydata.References;
import org.fog.placement.Controller;
import org.fog.placement.LocationHandler;
import org.fog.placement.MicroservicesController;
import org.fog.placement.MicroservicesMobilityClusteringController;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementEdgewards;
import org.fog.placement.ModulePlacementMapping;
import org.fog.placement.PlacementLogicFactory;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;
import org.fog.utils.TimeKeeper;
import org.fog.utils.distribution.DeterministicDistribution;
import org.json.simple.parser.ParseException;
import java.io.IOException;
import java.util.*;

public class HealthCare {
	
	static List<FogDevice> fogNodes = new ArrayList<FogDevice>();
	static List<Sensor> sensors = new ArrayList<Sensor>();
	static List<Actuator> actuators = new ArrayList<Actuator>();
		static int numOfHospitals = 10;// Number of hospitals or fog nodes
	static int numOfPatientsPerHospital = 10;//Number of patients per hospital or sensors
	
	private static boolean CLOUD = false;
		
	public static void main(String[] args) {

		Log.printLine("Starting Health Monitoring Application!");

		try {
			Log.disable();
			int num_user = 1; // number of cloud users
			Calendar calendar = Calendar.getInstance();
			boolean trace_flag = false; // mean trace events

			CloudSim.init(num_user, calendar, trace_flag);

			String appId = "healthcare"; // identifier of the application
			
			FogBroker broker = new FogBroker("broker");
			
			Application application = createApplication(appId, broker.getId());
			application.setUserId(broker.getId());
			
			createfogNodes(broker.getId(), appId);
			
			Controller controller = null;
			
			ModuleMapping moduleMapping = ModuleMapping.createModuleMapping(); // initializing a module mapping
			for(FogDevice device : fogNodes){
				if(device.getName().startsWith("p")){ // Name of each patient starts with 'p'
					moduleMapping.addModuleToDevice("patient_data", device.getName());  
					// Since the sensors attached to the patient would generate continuous
                    // real-time data, we fix an instance of the module 'patient_data' to each PATIENT
				}
			}
			for(FogDevice device : fogNodes){
				if(device.getName().startsWith("h")){ // Name of each hospital starts with 'h'
					moduleMapping.addModuleToDevice("check_for_emergency", device.getName());   
					// Since the fog node checks if the real-time data crosses a certain threshold or not,
					  // we fix an instance of the module 'check_for_emergency' to each fog device
				}
			}
			
			controller = new Controller("master-controller", fogNodes, sensors, 
					actuators); //performs the simulation
			
			controller.submitApplication(application, 
					(new ModulePlacementEdgewards(fogNodes, sensors, actuators, application, moduleMapping)));
			if(CLOUD){
				// if the mode of deployment is cloud-based
				moduleMapping.addModuleToDevice("patient_data", "cloud"); // placing all instances of "patient_data module" in the Cloud
				moduleMapping.addModuleToDevice("check_for_emergency", "cloud"); // placing all instances of "check_for_emergency" module in the Cloud
			}
			
			controller = new Controller("master-controller", fogNodes, sensors, actuators);
			
			controller.submitApplication(application,(CLOUD)?(new ModulePlacementMapping(fogNodes, application, moduleMapping))
							:(new ModulePlacementEdgewards(fogNodes, sensors, actuators, application, moduleMapping)));
			
			TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());
			
			CloudSim.startSimulation();

			CloudSim.stopSimulation();

			} 
		catch (Exception e) {
			e.printStackTrace();
			Log.printLine("An error occurred!");
		}
	}
	
	/**
	 * Creates the fog devices in the physical topology of the simulation.
	 * @param userId
	 * @param appId
	 */
	private static void createfogNodes(int userId, String appId) {
		FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 1648, 1332);
		cloud.setParentId(-1);
		fogNodes.add(cloud);
		FogDevice proxy = createFogDevice("proxy_server", 2800, 4000, 10000, 10000, 1, 0.0, 107.339, 83.4333);
		proxy.setParentId(cloud.getId());
		proxy.setUplinkLatency(100); // connection latency bw proxy server and cloud
		fogNodes.add(proxy);
		for(int i=0;i<numOfHospitals;i++){
			addHospital(i+"", userId, appId, proxy.getId());
		}
	}

	private static FogDevice addHospital(String id, int userId, String appId, int parentId){
		FogDevice router = createFogDevice("d-"+id, 2800, 4000, 10000, 10000, 2, 0.0, 107.339, 83.4333);
		fogNodes.add(router);
		router.setUplinkLatency(2); // latency of connection between router and proxy server is 2 ms
		for(int i=0;i<numOfPatientsPerHospital;i++){
			String mobileId = id+"-"+i;
			FogDevice Patient = addPatient(mobileId, userId, appId, router.getId()); // adding a smart Patient to the physical topology. Smart Patients have been modeled as fog devices as well.
			Patient.setUplinkLatency(2); // latency of connection between Patient and router is 2 ms
			fogNodes.add(Patient);
		}
		router.setParentId(parentId);
		return router;
	}
	
	private static FogDevice addPatient(String id, int userId, String appId, int parentId){
		FogDevice Patient = createFogDevice("m-"+id, 500, 1000, 10000, 10000, 3, 0, 87.53, 82.44);
		Patient.setParentId(parentId);
		Sensor sensor = new Sensor("s-"+id, "PATIENT", userId, appId, new DeterministicDistribution(5)); // inter-transmission time of Patient (sensor) follows a deterministic distribution
		sensors.add(sensor);
		Actuator ptz = new Actuator("ptz-"+id, userId, appId, "PTZ_CONTROL");
		actuators.add(ptz);
		sensor.setGatewayDeviceId(Patient.getId());
		sensor.setLatency(1.0);  // latency of connection between each Patient (sensor) and the parent Smart Patient is 1 ms
		ptz.setGatewayDeviceId(Patient.getId());
		ptz.setLatency(1.0);  // latency of connection between PTZ Control and the parent Smart Patient is 1 ms
		return Patient;
	}
	
	/**
	 * Creates a vanilla fog device
	 * @param nodeName name of the device to be used in simulation
	 * @param mips MIPS
	 * @param ram RAM
	 * @param upBw uplink bandwidth
	 * @param downBw downlink bandwidth
	 * @param level hierarchy level of the device
	 * @param ratePerMips cost rate per MIPS used
	 * @param busyPower
	 * @param idlePower
	 * @return
	 */
	private static FogDevice createFogDevice(String nodeName, long mips,
			int ram, long upBw, long downBw, int level, double ratePerMips, double busyPower, double idlePower) {
		
		List<Pe> peList = new ArrayList<Pe>();

		// 3. Create PEs and add these into a list.
		peList.add(new Pe(0, new PeProvisionerOverbooking(mips))); // need to store Pe id and MIPS Rating

		int hostId = FogUtils.generateEntityId();
		long storage = 1000000; // host storage
		int bw = 10000;

		PowerHost host = new PowerHost(
				hostId,
				new RamProvisionerSimple(ram),
				new BwProvisionerOverbooking(bw),
				storage,
				peList,
				new StreamOperatorScheduler(peList),
				new FogLinearPowerModel(busyPower, idlePower)
			);

		List<Host> hostList = new ArrayList<Host>();
		hostList.add(host);

		String arch = "x86"; // system architecture
		String os = "Linux"; // operating system
		String vmm = "Xen";
		double time_zone = 10.0; // time zone this resource located
		double cost = 3.0; // the cost of using processing in this resource
		double costPerMem = 0.05; // the cost of using memory in this resource
		double costPerStorage = 0.001; // the cost of using storage in this
										// resource
		double costPerBw = 0.0; // the cost of using bw in this resource
		LinkedList<Storage> storageList = new LinkedList<Storage>(); // we are not adding SAN
													// devices by now

		FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
				arch, os, vmm, host, time_zone, cost, costPerMem,
				costPerStorage, costPerBw);

		FogDevice fogdevice = null;
		try {
			fogdevice = new FogDevice(nodeName, characteristics, 
					new AppModuleAllocationPolicy(hostList), storageList, 10, upBw, downBw, 0, ratePerMips);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		fogdevice.setLevel(level);
		return fogdevice;
	}

	/**
	 * Function to create the Intelligent Surveillance application in the DDF model. 
	 * @param appId unique identifier of the application
	 * @param userId identifier of the user of the application
	 * @return
	 */
	@SuppressWarnings({"serial" })
	private static Application createApplication(String appId, int userId){
		
		Application application = Application.createApplication(appId, userId);
		/*
		 * Adding modules (vertices) to the application model (directed graph)
		 */
		application.addAppModule("patient_data", 10);
		application.addAppModule("check_for_emergency", 10);
		application.addAppEdge("PATIENT", "patient_data", 1000, 500, "PATIENT", Tuple.UP, AppEdge.SENSOR); // adding edge from PATIENT to take_picture module 
		application.addAppEdge("patient_data", "check_for_emergency", 1000, 500, "slots",Tuple.UP, AppEdge.MODULE);
				
		application.addAppEdge("check_for_emergency", "PTZ_CONTROL", 100, 28, 100, "PTZ_PARAMS", Tuple.DOWN, AppEdge.ACTUATOR);
		application.addTupleMapping("patient_data", "PATIENT", "slots",
		new FractionalSelectivity(1.0));
		application.addTupleMapping("check_for_emergency", "slots",
		"PTZ_PARAMS", new FractionalSelectivity(1.0));
		final AppLoop loop = new AppLoop(new ArrayList<String>()
		{{add("PATIENT");
		add("patient_data");add("check_for_emergency");
		add("PTZ_CONTROL");}});
		List<AppLoop> loops = new ArrayList<AppLoop>(){{add(loop);}};
		application.setLoops(loops);
		return application;
	}


	}