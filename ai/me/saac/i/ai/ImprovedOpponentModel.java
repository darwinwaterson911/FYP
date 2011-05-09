package me.saac.i.ai;

import java.util.HashMap;

import me.saac.i.ai.GameState.Action;
import weka.classifiers.Classifier;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.functions.MultilayerPerceptron;
import weka.classifiers.trees.J48;
import weka.core.*;


public class ImprovedOpponentModel implements OpponentModel {

	Attribute opponentRaiseFrequency = new Attribute("opponentRaiseFrequency");
	Attribute totalRaiseFrequency = new Attribute("playerRaiseFrequency");
	Attribute opponentsLastActionWasRaise = new Attribute("opponentsLastActionWasRaise");
	Attribute potOdds = new Attribute("potOdds");
	Attribute nextMove;
	Attribute currentRound;
	int numAttrs = 6;
	
	Classifier actionClassifier = null;
	Instances actionData;
	
	// for calculating adjusted hand rank
	int[] boundaries = {
			1602,
			540,
			488,
			238,
			245,
			258,
			430,
			441,
			440,
			441,
			441,
			422,
			298,
			430,
			1000};
	
    // Hash Map which maps (number of raises) to an int array
    // representing the histogram for that number of raises
	HashMap<Integer, int[]> historyToHandStrength = new HashMap<Integer, int[]>();

	public ImprovedOpponentModel() {
		FastVector fv = new FastVector(3);
		fv.addElement("FOLD");
		fv.addElement("CHECK");
		fv.addElement("RAISE");
		nextMove = new Attribute("nextMove", fv);
		
		fv = new FastVector(4);
		fv.addElement("PREFLOP");
		fv.addElement("FLOP");
		fv.addElement("TURN");
		fv.addElement("RIVER");
		currentRound = new Attribute("currentRound", fv);
		
		FastVector attrs = new FastVector(numAttrs);
		attrs.addElement(opponentRaiseFrequency);
		attrs.addElement(totalRaiseFrequency);
		attrs.addElement(nextMove);
		attrs.addElement(currentRound);
		attrs.addElement(potOdds);
		attrs.addElement(opponentsLastActionWasRaise);
		
		actionData = new Instances("actionData", attrs, 100);
		actionData.setClass(nextMove);

	}
	
    // takes current history and player hand strength and calculates win probability
	public double winPossibility(ActionList history, GameInfo gameInfo, int playerHandStrength) {
	    // find number of raise actions in history
		int numRaises = history.opponentActions(gameInfo).numberOfRaises();
		
		// find histogram and adjusted hand strength
		int[] histogram = historyToHandStrength.get(numRaises);
		int aphs = adjustedHandStrength(playerHandStrength);
		
		// if there is no observed history for this number of raises,
		// return a default probability based on adjusted hand strength
		if(histogram == null)
			return 1.0 - ((double) aphs / 16);
		
		
		// calculate win% = hands player can defeat / total hands
		int handsPlayerWins = 0;
		int handsOpponentWins = 0;
		int i;
		for(i = 0; i < aphs; i++) {
			handsOpponentWins += histogram[i];
		}
		for(; i < histogram.length; i++) {
			handsPlayerWins += histogram[i];
		}
		
		return (double) handsPlayerWins / (handsPlayerWins + handsOpponentWins);
	}
	
	public void updateHistogram(ActionList history, GameInfo gameInfo, int handStrength) {
		int numRaises = history.opponentActions(gameInfo).numberOfRaises();
		
		// calculate adjusted hand strength
		int ahs = adjustedHandStrength(handStrength);

		// if there is no history, initialize an empty int array
		if(historyToHandStrength.get(numRaises) == null) {
			int[] histogram = new int[] {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};
			historyToHandStrength.put(numRaises, histogram);
		}

		// find existing histogram and increment appropriate slot
		int[] histogram = historyToHandStrength.get(numRaises);
		histogram[ahs] = histogram[ahs] + 1;
	
		System.out.print("OpponentModel["+numRaises+"] <- ");
		for(int i = 0; i < histogram.length; i++) {
			System.out.print(histogram[i] + " - ");
		}		
	}
	
    // when an opponent action is received, update the opponent model
	// (last action in history is the opponents latest action)
	public void inputAction(ActionList h, GameState gameState) {
		
		// since last action is history is the opponent's latest action, remove it from the list
		// (since it's the thing we're trying to predict)
		ActionList history = (ActionList) h.clone();
		Action nextAction = history.lastAction();
		history.remove(history.size() - 1); 
		
		Instance newInstance = gameStateToInstance(history, gameState, nextAction);
		actionData.add(newInstance);
		System.out.println("Adding training instance: " + newInstance.toString());
	}
	
	private Instance gameStateToInstance(ActionList history, GameState gameState, Action moveMade) {
		Instance newInstance = new Instance(numAttrs);
		newInstance.setValue(currentRound, gameState.bettingRound.toString());
		
		ActionList opponentActions = history.opponentActions(gameState.gameInfo);
		
		double orf = (double) opponentActions.numberOfRaises() / opponentActions.size();
		double trf =  (double) history.numberOfRaises() / history.size();
		newInstance.setValue(opponentRaiseFrequency, orf);
		newInstance.setValue(totalRaiseFrequency, trf);
		
		double po = ((double) (gameState.playerBetAmount - gameState.opponentBetAmount)) /  
			(gameState.playerBetAmount + gameState.opponentBetAmount);
		newInstance.setValue(potOdds, po);
		
		if(opponentActions.size() > 0 && opponentActions.lastAction() == Action.RAISE) {
			newInstance.setValue(opponentsLastActionWasRaise, 1);
		} else {
			newInstance.setValue(opponentsLastActionWasRaise, 0);
		}
	
		if(moveMade != null) {
			newInstance.setValue(nextMove, moveMade.toString());
		}
		
		return newInstance;
			
	}
	
    // when a hand has finished, update the opponent model
	public void inputEndOfHand(ActionList history, GameState gameState) {
		int handStrength = gameState.knownCards.evaluateOpponentHand();
		if(handStrength != -1) {
			updateHistogram(history, gameState.gameInfo, handStrength);
		}
		
		try {
			int numI = actionData.numInstances();
			if(numI >= 1 && (numI < 200 || (numI % 100 < 6 && numI < 1000) || numI % 500 < 6)) {
				System.out.println("numInstances: " + actionData.numInstances());
				actionClassifier = new NaiveBayes();
				actionClassifier.buildClassifier(actionData);
				System.out.println("\n"+actionClassifier.toString());
				
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		
	}
	
	private int adjustedHandStrength(int rawHandStrength) {
		int ahs = 0;
		for(int j = 0; j < 15; j++) {
			if(rawHandStrength < boundaries[j]) {
				ahs = j;
				break;
			} else if(j == 14) {
				ahs = j;
				break;
			} else {
				rawHandStrength -= boundaries[j];
			}
		}
		return Math.min(ahs, 15);
	}
	
	public double[] actionProbabilities(ActionList history, GameState gameState) {
		Instance currentState = gameStateToInstance(history, gameState, null);
		currentState.setDataset(actionData);
		double[] ap = {0.5, 0.5, 0.0}; // defaults for first hand
		if(actionClassifier != null) {
			try {
				ap = actionClassifier.distributionForInstance(currentState);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				System.exit(0);
			}
		}
		return ap;
	}
}
