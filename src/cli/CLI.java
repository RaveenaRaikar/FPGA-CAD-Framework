package cli;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Random;

import packers.BlePacker;
import packers.ClbPacker;
import placers.Placer;
import placers.SAPlacer.EfficientBoundingBoxNetCC;
import placers.random.RandomPlacer;
import timinganalysis.TimingGraph;

import architecture.Architecture;
import circuit.BlePackedCircuit;
import circuit.PackedCircuit;
import circuit.PrePackedCircuit;
import circuit.parser.blif.BlifReader;
import circuit.parser.net.NetReader;
import circuit.parser.placement.PlaceParser;
import cli.Options;


public class CLI {
	
	private static long timerBegin, timerEnd;
	
	public static void main(String[] args) {
		
		// The Options class can parse the command line options
		Options options = new Options();
		options.parseArguments(args);
		
		
		// Get the circuit
		PrePackedCircuit prePackedCircuit = null;
		PackedCircuit packedCircuit = null;
		
		// Get the number of lut inputs (depends on used architecture)
		int nbLutInputs = Architecture.getNbLutInputs(options.architecture);
		
		// If the circuit should be packed: read the blif file
		if(options.startingStage.equals("blif")) {
			BlifReader blifReader = new BlifReader();
			
			try {
				prePackedCircuit = blifReader.readBlif(options.blifFile.toString(), nbLutInputs);
			} catch(IOException e) {
				error("Failed to read blif file: " + options.blifFile.toString());
			}
			
			BlePacker blePacker = new BlePacker(prePackedCircuit);
			BlePackedCircuit blePackedCircuit = blePacker.pack();
		
			ClbPacker clbPacker = new ClbPacker(blePackedCircuit);
			packedCircuit = clbPacker.pack();
			
		
		// Else: read the net file 
		} else {
			NetReader netReader = new NetReader();
			
			CLI.startTimer();
			try {
				netReader.readNetlist(options.netFile.toString(), nbLutInputs);
			} catch(IOException e) {
				error("Failed to read net file: " + options.netFile.toString());
			}
			CLI.stopTimer();
			System.out.println(CLI.getTimer());
			
			prePackedCircuit = netReader.getPrePackedCircuit();
			packedCircuit = netReader.getPackedCircuit();
		}
		
		packedCircuit.setName(options.circuit);
		
		
		
		// Set the architecture
		// TODO: IOSiteCapacity flexible
		Architecture architecture = Architecture.newArchitecture(options.architecture, packedCircuit, 1);
		if(architecture == null) {
			error("Architecture type not recognized: " + options.architecture);
		}
		
		
		
		// If a random initialization is required: do it
		if(options.random) {
			Random rand = new Random(1);
			RandomPlacer.placeCLBsandFixedIOs(packedCircuit, architecture, rand);
			
			CLI.printStatistics("initial", prePackedCircuit, packedCircuit, false);
		}
		
		// Read the place file
		if(options.startingStage.equals("place")) {
			PlaceParser placeParser = new PlaceParser(architecture, packedCircuit, options.placeFile);
			placeParser.parse();
			
			CLI.printStatistics("parser", prePackedCircuit, packedCircuit, false);
		}
		
		
		// Loop through the placers
		for(int i = 0; i < options.placers.size(); i++) {
			String placerName = options.placers.get(i);
			HashMap<String, String> placerOptions = options.placerOptions.get(i);
			
			System.out.println("Placing with " + placerName + "...");
			
			
			
			// Create the placer and place the circuit
			CLI.startTimer();
			Placer placer = Placer.newPlacer(placerName, architecture, prePackedCircuit, packedCircuit, placerOptions);
			placer.place();
			CLI.stopTimer();
			
			CLI.printStatistics(placerName, prePackedCircuit, packedCircuit);
		}
		
		
		
		// Print out the place file
		try {
			packedCircuit.dumpPlacement(architecture, options.outputFile.toString());
		} catch (FileNotFoundException e) {
			error("Place file not found: " + options.placeFile);
		}
	}
	
	private static void startTimer() {
		CLI.timerBegin = System.nanoTime();
	}
	private static void stopTimer() {
		CLI.timerEnd = System.nanoTime();
	}
	private static double getTimer() {
		return (CLI.timerEnd - CLI.timerBegin) * 1e-9;
	}
	
	private static void printStatistics(String prefix, PrePackedCircuit prePackedCircuit, PackedCircuit packedCircuit) {
		CLI.printStatistics(prefix, prePackedCircuit, packedCircuit, true);
	}
	
	private static void printStatistics(String prefix, PrePackedCircuit prePackedCircuit, PackedCircuit packedCircuit, boolean printTime) {
		
		System.out.println();
		
		if(printTime) {	
			double placeTime = CLI.getTimer();
			System.out.format("%s %15s: %f s\n", prefix, "place time", placeTime);
		}
		
		EfficientBoundingBoxNetCC effcc = new EfficientBoundingBoxNetCC(packedCircuit);
		double totalCost = effcc.calculateTotalCost();
		System.out.format("%s %15s: %f\n", prefix, "total cost", totalCost);
		
		TimingGraph timingGraph = new TimingGraph(prePackedCircuit);
		timingGraph.buildTimingGraph();
		double maxDelay = timingGraph.calculateMaximalDelay();
		System.out.format("%s %15s: %f\n", prefix, "max delay", maxDelay);
		
		System.out.format("%s %15s: %f\n", prefix, "T cost", timingGraph.calculateTotalCost());
		
		System.out.println();
	}
	
	
	private static void error(String error) {
		System.err.println(error);
		System.exit(1);
	}

}
