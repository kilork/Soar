package soar2d;

import java.awt.*;
import java.util.*;
import java.util.logging.*;

import soar2d.player.*;
import soar2d.world.*;

class MissileInfo {
	java.awt.Point location;
	int direction;
}

public class World {

	private static Logger logger = Logger.getLogger("soar2d");

	private int worldCount = 0;
	private boolean printedStats = false;
	
	public GridMap map;
	
	private ArrayList<Player> players = new ArrayList<Player>(7);
	private ArrayList<Player> humanPlayers = new ArrayList<Player>(7);
	private HashMap<String, Player> playersMap = new HashMap<String, Player>(7);
	private HashMap<String, java.awt.Point> initialLocations = new HashMap<String, java.awt.Point>(7);
	private HashMap<String, java.awt.Point> locations = new HashMap<String, java.awt.Point>(7);
	private HashMap<String, MoveInfo> lastMoves = new HashMap<String, MoveInfo>(7);
	private HashMap<String, HashSet<Player> > killedTanks = new HashMap<String, HashSet<Player> >(7);
	private HashMap<String, MissileInfo> missiles = new HashMap<String, MissileInfo>();
	
	private int missileID = 0;
	private int missileReset = 0;
	
	public boolean load() {
		assert logger.isLoggable(Soar2D.config.logLevel); 

		MapLoader loader = new MapLoader();
		if (!loader.load()) {
			return false;
		}
		
		GridMap newMap = loader.getMap();
		
		if (Soar2D.config.tanksoar) {
			if (!newMap.hasEnergyCharger()) {
				if (!addCharger(false, newMap)) {
					return false;
				}
			}
			if (!newMap.hasHealthCharger()) {
				if (!addCharger(true, newMap)) {
					return false;
				}
			}
			
		}
		
		map = newMap;
		
		if (Soar2D.config.tanksoar) {
			// Spawn missile packs
			while (map.numberMissilePacks() < Soar2D.config.kMaxMissilePacks) {
				spawnMissilePack(true);
			}
		}
		
		reset();
		resetPlayers();
		
		logger.info("Map loaded, world reset.");
		return true;
	}
	
	private boolean addCharger(boolean health, GridMap newMap) {
		ArrayList<java.awt.Point> locations = this.getAvailableLocations(newMap);
		if (locations.size() <= 0) {
			Soar2D.control.severeError("No place to put charger!");
			return false;
		}
		
		java.awt.Point location = locations.get(Simulation.random.nextInt(locations.size()));
		if (health) {
			logger.info("spawning health charger at (" + location.x + "," + location.y + ")");
			if (!newMap.addRandomObjectWithProperties(location, Names.kPropertyHealth, Names.kPropertyCharger)) {
				Soar2D.control.severeError("Couldn't add charger to map!");
				return false;
			}
		} else {			
			logger.info("spawning energy charger at (" + location.x + "," + location.y + ")");
			if (!newMap.addRandomObjectWithProperties(location, Names.kPropertyEnergy, Names.kPropertyCharger)) {
				Soar2D.control.severeError("Couldn't add charger to map!");
				return false;
			}
		}
		
		return true;
	}
	
	void resetPlayers() {
		if (players.size() == 0) {
			return;
		}
		Iterator<Player> iter = players.iterator();
		while (iter.hasNext()) {
			// for each player
			Player player = iter.next();
			
			resetPlayer(player);
		}
		
		updatePlayers(false);
	}
	
	private boolean resetPlayer(Player player) {
		// find a suitable starting location
		java.awt.Point startingLocation = putInStartingLocation(player);
		if (startingLocation == null) {
			return false;
		}
		
		if (Soar2D.config.eaters) {
			// remove food from it
			map.removeAllWithProperty(startingLocation, Names.kPropertyEdible);
			
			// reset (init-soar)
			player.reset();
			
		} else if (Soar2D.config.tanksoar) {
			player.reset();
		}
		return true;
	}
	
	private void updatePlayers(boolean playersChanged) {
		Iterator<Player> iter = players.iterator();
		while (iter.hasNext()) {
			Player player = iter.next();
			
			if (Soar2D.config.tanksoar) {
				if (playersChanged) {
					player.playersChanged();
				}
				if (players.size() < 2) {
					player.setSmell(0, null);
					player.setSound(0);
				} else {
					int distance = 99;
					String color = null;
					
					Iterator<Player> smellIter = players.iterator();
					while (smellIter.hasNext()) {
						
						Player other = smellIter.next();
						if (other.equals(player)) {
							continue;
						}
						
						java.awt.Point playerLoc = locations.get(player.getName());
						java.awt.Point otherLoc = locations.get(other.getName());
						int newDistance = Math.abs(playerLoc.x - otherLoc.x) + Math.abs(playerLoc.y - otherLoc.y);
						
						if (newDistance < distance) {
							distance = newDistance;
							color = other.getColor();
						} else if (newDistance == distance) {
							if (Simulation.random.nextBoolean()) {
								distance = newDistance;
								color = other.getColor();
							}
						}
					}
					player.setSmell(distance, color);
					// TODO: can eliminate sound check if smell is greater than max sound distance 
					player.setSound(map.getSoundNear(locations.get(player.getName())));
				}
				
			}
			player.update(this, locations.get(player.getName()));
		}
		
		if (Soar2D.config.tanksoar) {
			Iterator<Player> playerIter = players.iterator();
			while (playerIter.hasNext()) {
				Player player = playerIter.next();
				player.commit(locations.get(player.getName()));
			}
		}
	}
	
	public void shutdown() {
		Iterator<Player> iter = players.iterator();
		while (iter.hasNext()) {
			Soar2D.simulation.destroyPlayer(iter.next());
			iter = players.iterator();
		}
		assert this.players.size() == 0;
		assert this.humanPlayers.size() == 0;
		assert this.playersMap.size() == 0;
		assert this.initialLocations.size() == 0;
		assert this.locations.size() == 0;
		assert this.lastMoves.size() == 0;
		map.shutdown();
	}
	
	public void removePlayer(String name) {
		Player player = playersMap.get(name);
		if (player == null) {
			logger.warning("destroyPlayer: Couldn't find player name match for " + name + ", ignoring.");
			return;
		}
		players.remove(player);
		humanPlayers.remove(player);
		playersMap.remove(name);
		initialLocations.remove(name);
		java.awt.Point location = locations.remove(name);
		lastMoves.remove(name);
		map.setPlayer(location, null);
		
		updatePlayers(true);
	}
	
	private ArrayList<java.awt.Point> getAvailableLocations(GridMap theMap) {
		ArrayList<java.awt.Point> availableLocations = new ArrayList<java.awt.Point>();
		for (int x = 0; x < theMap.getSize(); ++x) {
			for (int y = 0; y < theMap.getSize(); ++ y) {
				java.awt.Point potentialLocation = new java.awt.Point(x, y);
				if (theMap.isAvailable(potentialLocation)) {
					availableLocations.add(potentialLocation);
				}
			}
		}
		return availableLocations;
	}
	
	private java.awt.Point putInStartingLocation(Player player) {
		// Get available cells
		
		ArrayList<java.awt.Point> availableLocations = getAvailableLocations(map);
		// make sure there is an available cell
		if (Soar2D.config.tanksoar) {
			// There must be enough room for all tank and missile packs
			if (availableLocations.size() < Soar2D.config.kMaxMissilePacks + 1) {
				Soar2D.control.severeError("There are no suitable starting locations for " + player.getName() + ".");
				return null;
			}
		} else {
			if (availableLocations.size() < 1) {
				Soar2D.control.severeError("There are no suitable starting locations for " + player.getName() + ".");
				return null;
			}
		}
		
		java.awt.Point location = null;

		if (initialLocations.containsKey(player.getName())) {
			location = initialLocations.get(player.getName());
			if (!availableLocations.contains(location)) {
				logger.warning(player.getName() + ": Initial location (" + location.x + "," + location.y + ") is blocked, going random.");
				location = null;
			}
		}
		
		if (location == null) {
			location = availableLocations.get(Simulation.random.nextInt(availableLocations.size()));
		}
		
		// put the player in it
		map.setPlayer(location, player);
		locations.put(player.getName(), location);
		return location;
	}

	public boolean addPlayer(Player player, java.awt.Point initialLocation, boolean human) {
		assert !locations.containsKey(player.getName());
		
		players.add(player);
		playersMap.put(player.getName(), player);
		
		if (initialLocation != null) {
			initialLocations.put(player.getName(), initialLocation);
		}
		
		if (!resetPlayer(player)) {
			initialLocations.remove(player.getName());
			players.remove(player);
			playersMap.remove(player.getName());
			return false;
		}
		java.awt.Point location = locations.get(player.getName());
		
		logger.info(player.getName() + ": Spawning at (" + 
				location.x + "," + location.y + ")");
		
		if (human) {
			humanPlayers.add(player);
		}

		updatePlayers(true);
		return true;
	}
	
	private void moveEaters() {
		Iterator<Player> iter = players.iterator();
		while (iter.hasNext()) {
			Player player = iter.next();
			MoveInfo move = getAndStoreMove(player);			
			if (move == null) {
				return;
			}

			if (!move.move) {
				continue;
			}

			// Calculate new location
			java.awt.Point oldLocation = locations.get(player.getName());
			java.awt.Point newLocation = new java.awt.Point(oldLocation);
			Direction.translate(newLocation, move.moveDirection);
			if (move.jump) {
				Direction.translate(newLocation, move.moveDirection);
			}
			
			// Verify legal move and commit move
			if (map.isInBounds(newLocation) && map.enterable(newLocation)) {
				// remove from cell
				map.setPlayer(oldLocation, null);
				
				if (move.jump) {
					player.adjustPoints(Soar2D.config.kJumpPenalty, "jump penalty");
				}
				locations.put(player.getName(), newLocation);
				
			} else {
				player.adjustPoints(Soar2D.config.kWallPenalty, "wall collision");
			}
		}
	}
	
	private void stopAndDumpStats(String message, int[] scores) {
		if (!printedStats) {
			printedStats = true;
			Soar2D.control.infoPopUp(message);
			Soar2D.control.stopSimulation();
			boolean draw = false;
			if (scores.length > 1) {
				if (scores[scores.length - 1] ==  scores[scores.length - 2]) {
					if (logger.isLoggable(Level.FINER)) logger.finer("Draw detected.");
					draw = true;
				}
			}
			
			Iterator<Player> iter = players.iterator();
			while (iter.hasNext()) {
				String status = null;
				Player player = iter.next();
				if (player.getPoints() == scores[scores.length - 1]) {
					status = draw ? "draw" : "winner";
				} else {
					status = "loser";
				}
				logger.info(player.getName() + ": " + player.getPoints() + " (" + status + ").");
			}
		}
	}
	
	public int getSize() {
		return map.getSize();
	}

	private void updateMapAndEatFood() {
		Iterator<Player> iter = players.iterator();
		while (iter.hasNext()) {
			Player player = iter.next();
			MoveInfo lastMove = lastMoves.get(player.getName());
			java.awt.Point location = locations.get(player.getName());
			
			if (lastMove.move || lastMove.jump) {
				map.setPlayer(location, player);

				ArrayList<CellObject> moveApply = map.getAllWithProperty(location, Names.kPropertyMoveApply);
				if (moveApply.size() > 0) {
					Iterator<CellObject> maIter = moveApply.iterator();
					while (maIter.hasNext()) {
						CellObject object = maIter.next();
						if (object.apply(player)) {
							map.removeObject(location, object.getName());
						}
					}
				}
			}
			
			if (!lastMove.dontEat) {
				eat(player, location);
			}
			
			if (lastMove.open) {
				open(player, location);
			}
		}
	}
	
	private void eat(Player player, java.awt.Point location) {
		ArrayList<CellObject> list = map.getAllWithProperty(location, Names.kPropertyEdible);
		Iterator<CellObject> foodIter = list.iterator();
		while (foodIter.hasNext()) {
			CellObject food = foodIter.next();
			if (food.apply(player)) {
				// if this returns true, it is consumed
				map.removeObject(location, food.getName());
			}
		}
	}
	
	public void missileHit(Player player, java.awt.Point location, CellObject missile) {
		// Yes, I'm hit
		missile.apply(player);
		
		// apply points
		player.adjustPoints(Soar2D.config.kMissileHitPenalty, missile.getName());
		Player other = playersMap.get(missile.getProperty(Names.kPropertyOwner));
		other.adjustPoints(Soar2D.config.kMissileHitAward, missile.getName());
		
		// charger insta-kill
		if (map.getAllWithProperty(location, Names.kPropertyCharger).size() > 0) {
			player.adjustHealth(player.getHealth() * -1, "hit on charger");
		}
		
		// check for kill
		if (player.getHealth() <= 0) {
			HashSet<Player> assailants = killedTanks.get(player.getName());
			if (assailants == null) {
				assailants = new HashSet<Player>();
			}
			assailants.add(other);
			killedTanks.put(player.getName(), assailants);
		}
	}
	
	private void open(Player player, java.awt.Point location) {
		ArrayList<CellObject> boxes = map.getAllWithProperty(location, Names.kPropertyBox);
		if (boxes.size() <= 0) {
			Soar2D.logger.warning(player.getName() + " tried to open but there is no box.");
			return;
		}

		// TODO: multiple boxes
		assert boxes.size() <= 1;
		
		CellObject box = boxes.get(0);
		if (box.hasProperty(Names.kPropertyStatus)) {
			if (box.getProperty(Names.kPropertyStatus).equalsIgnoreCase(Names.kOpen)) {
				Soar2D.logger.warning(player.getName() + " tried to open an open box.");
				return;
			}
		}
		if (box.apply(player)) {
			map.removeObject(location, box.getName());
		}
	}
	
	private int[] getSortedScores() {
		int[] scores = new int[players.size()];
		Iterator<Player> iter = players.iterator();
		int i = 0;
		Player player;
		while (iter.hasNext()) {
			player = iter.next();
			scores[i] = player.getPoints();
			++i;
		}
		Arrays.sort(scores);
		return scores;
	}
	
	public void update() {
		
		// Collect human input
		Iterator<Player> humanPlayerIter = humanPlayers.iterator();
		while (humanPlayerIter.hasNext()) {
			Player human = humanPlayerIter.next();
			if (!human.getHumanMove()) {
				return;
			}
		}
		
		++worldCount;

		if (Soar2D.config.terminalMaxUpdates > 0) {
			if (worldCount >= Soar2D.config.terminalMaxUpdates) {
				stopAndDumpStats("Reached maximum updates, stopping.", getSortedScores());
				return;
			}
		}
		
		if (Soar2D.config.terminalWinningScore > 0) {
			int[] scores = getSortedScores();
			if (scores[scores.length - 1] >= Soar2D.config.terminalWinningScore) {
				stopAndDumpStats("At least one player has achieved at least " + Soar2D.config.terminalWinningScore + " points.", scores);
				return;
			}
		}
		
		if (Soar2D.config.terminalPointsRemaining) {
			if (map.getScoreCount() <= 0) {
				stopAndDumpStats("There are no points remaining.", getSortedScores());
				return;
			}
		}

		if (Soar2D.config.terminalFoodRemaining) {
			if (map.getFoodCount() <= 0) {
				stopAndDumpStats("All of the food is gone.", getSortedScores());
				return;
			}
		}

		if (Soar2D.config.terminalUnopenedBoxes) {
			if (map.getUnopenedBoxCount() <= 0) {
				stopAndDumpStats("All of the boxes are open.", getSortedScores());
				return;
			}
		}

		if (players.size() == 0) {
			logger.warning("Update called with no players.");
			return;
		}
		
		if (Soar2D.config.eaters) {
			eatersUpdate();
		} else if (Soar2D.config.tanksoar) {
			tankSoarUpdate();
		} else {
			Soar2D.control.severeError("Update called, unknown game type.");
		}
	}
	
	private void eatersUpdate() {
		moveEaters();
		if (Soar2D.control.isShuttingDown()) {
			return;
		}
		updateMapAndEatFood();
		handleCollisions();	
		updatePlayers(false);
		updateObjects();
	}
	
	private void tankSoarUpdate() {
		
		// Read Tank output links
		getTankMoves();
		
		// We'll cache the tank new locations
		HashMap<String, java.awt.Point> newLocations = new HashMap<String, java.awt.Point>();
		
		// And we'll cache tanks that moved
		ArrayList<Player> movedTanks = new ArrayList<Player>(players.size());
		
		// And we'll cache tanks that fired
		ArrayList<Player> firedTanks = new ArrayList<Player>(players.size());
		
		// We need to keep track of killed tanks, reset the list
		killedTanks.clear();
		
		// Cache players who fire (never fails)
		// Rotate players (never fails)
		// Update shields & consume shield energy
		// Update radar & consume radar energy
		// Do cross checks (and only cross checks) first
		// Cross-check:
		// If moving in to a cell with a tank, check that tank for 
		// a move in the opposite direction
		Iterator<Player> playerIter = players.iterator();
		while (playerIter.hasNext()) {
			Player player = playerIter.next();
			
			MoveInfo playerMove = lastMoves.get(player.getName());
			
			// Check for fire
			if (playerMove.fire) {
				if (player.getMissiles() > 0) {
					player.adjustMissiles(-1, "fire");
					firedTanks.add(player);
				} else {
					if (Soar2D.logger.isLoggable(Level.FINER)) logger.finer(player.getName() + ": fired with no ammo");
				}
			}
			
			// Check for rotate
			if (playerMove.rotate) {
				int facing = player.getFacingInt();
				if (playerMove.rotateDirection.equals(Names.kRotateLeft)) {
					facing = Direction.leftOf[facing];
				} else if (playerMove.rotateDirection.equals(Names.kRotateRight)) {
					facing = Direction.rightOf[facing];
				} else {
					logger.warning(player.getName() + ": unknown rotation command: " + playerMove.rotateDirection);
				}
				player.setFacingInt(facing);
			}
			
			// Check shields
			if (playerMove.shields) {
				player.setShields(playerMove.shieldsSetting);
			}
			
			// Radar
			if (playerMove.radar) {
				player.setRadarSwitch(playerMove.radarSwitch);
			}
			if (playerMove.radarPower) {
				player.setRadarPower(playerMove.radarPowerSetting);
			}

			// if we exist in the new locations, we can skip ourselves
			if (newLocations.containsKey(player.getName())) {
				continue;
			}
			
			// we haven't been checked yet
			
			// Calculate new location if I moved, or just use the old location
			java.awt.Point oldLocation = locations.get(player.getName());
			
			if (!playerMove.move) {
				// No move, cross collision impossible
				newLocations.put(player.getName(), oldLocation);
				continue;
			}
			
			// we moved, calcuate new location
			java.awt.Point newLocation = new java.awt.Point(oldLocation);
			Direction.translate(newLocation, playerMove.moveDirection);
			
			//Cell dest = map.getCell(newLocation);
			
			// Check for wall collision
			if (!map.enterable(newLocation)) {
				// Moving in to wall, there will be no player in that cell

				// Cancel the move
				playerMove.move = false;
				newLocations.put(player.getName(), locations.get(player.getName()));
				
				// take damage
				String name = map.getAllWithProperty(newLocation, Names.kPropertyBlock).get(0).getName();
				player.adjustHealth(Soar2D.config.kTankCollisionPenalty, name);
				
				if (player.getHealth() <= 0) {
					HashSet<Player> assailants = killedTanks.get(player.getName());
					if (assailants == null) {
						assailants = new HashSet<Player>();
					}
					assailants.add(player);
					killedTanks.put(player.getName(), assailants);
				}
				continue;
			}
			
			// The cell is enterable, check for player
			
			Player other = map.getPlayer(newLocation);
			if (other == null) {
				// No tank, cross collision impossible
				newLocations.put(player.getName(), newLocation);
				movedTanks.add(player);
				continue;
			}
			
			// There is another player, check its move
			
			MoveInfo otherMove = lastMoves.get(other.getName());
			if (!otherMove.move) {
				// they didn't move, cross collision impossible
				newLocations.put(player.getName(), newLocation);
				movedTanks.add(player);
				continue;
			}
			
			// the other player is moving, check its direction
			
			if (playerMove.moveDirection != Direction.backwardOf[otherMove.moveDirection]) {
				// we moved but not toward each other, cross collision impossible
				newLocations.put(player.getName(), newLocation);
				movedTanks.add(player);
				continue;
			}

			// Cross collision detected
			
			// take damage
			player.adjustHealth(Soar2D.config.kTankCollisionPenalty, "cross collision " + other.getName());
			other.adjustHealth(Soar2D.config.kTankCollisionPenalty, "cross collision " + player.getName());
			
			if (player.getHealth() <= 0) {
				HashSet<Player> assailants = killedTanks.get(player.getName());
				if (assailants == null) {
					assailants = new HashSet<Player>();
				}
				assailants.add(other);
				killedTanks.put(player.getName(), assailants);
			}
			if (other.getHealth() <= 0) {
				HashSet<Player> assailants = killedTanks.get(other.getName());
				if (assailants == null) {
					assailants = new HashSet<Player>();
				}
				assailants.add(player);
				killedTanks.put(other.getName(), assailants);
			}
			
			// cancel moves
			playerMove.move = false;
			otherMove.move = false;
			
			// store new locations
			newLocations.put(player.getName(), locations.get(player.getName()));
			newLocations.put(other.getName(), locations.get(other.getName()));
		}
		
		// We've eliminated all cross collisions and walls
		
		// We'll need to save where people move, indexed by location
		HashMap<java.awt.Point, ArrayList<Player> > collisionMap = new HashMap<java.awt.Point, ArrayList<Player> >();
		
		// Iterate through players, checking for all other types of collisions
		// Also, moves are committed at this point and they won't respawn on
		// a charger, so do charging here too
		// and shields and radar
		playerIter = players.iterator();
		while (playerIter.hasNext()) {
			Player player = playerIter.next();
			MoveInfo playerMove = lastMoves.get(player.getName());
			
			doMoveCollisions(player, playerMove, newLocations, collisionMap, movedTanks);

			// chargers
			chargeUp(player, newLocations.get(player.getName()));

			// Shields
			if (player.shieldsUp()) {
				if (player.getEnergy() > 0) {
					player.adjustEnergy(Soar2D.config.kSheildEnergyUsage, "shields");
				} else {
					if (Soar2D.logger.isLoggable(Level.FINER)) logger.finer(player.getName() + ": shields ran out of energy");
					player.setShields(false);
				}
			}
			
			// radar
			if (player.getRadarSwitch()) {
				handleRadarEnergy(player);
			}
		}			
		
		// figure out collision damage
		Iterator<ArrayList<Player> > locIter = collisionMap.values().iterator();
		while (locIter.hasNext()) {
			
			ArrayList<Player> collision = locIter.next();
			
			// if there is more than one player, have them all take damage
			if (collision.size() > 1) {
				
				int damage = collision.size() - 1;
				damage *= Soar2D.config.kTankCollisionPenalty;
				
				if (Soar2D.logger.isLoggable(Level.FINE)) logger.fine("Collision, " + (damage * -1) + " damage:");
				
				playerIter = collision.iterator();
				while (playerIter.hasNext()) {
					Player player = playerIter.next();
					player.adjustHealth(damage, "collision");

					
					// check for kill
					if (player.getHealth() <= 0) {
						HashSet<Player> assailants = killedTanks.get(player.getName());
						if (assailants == null) {
							assailants = new HashSet<Player>();
						}
						// give everyone else involved credit for the kill
						Iterator<Player> killIter = collision.iterator();
						while (killIter.hasNext()) {
							Player other = killIter.next();
							if (other.getName().equals(player.getName())) {
								continue;
							}
							assailants.add(other);
						}
						killedTanks.put(player.getName(), assailants);
					}
				}
			}
		}
		
		// Commit tank moves in two steps, remove from old, place in new
		playerIter = movedTanks.iterator();
		while (playerIter.hasNext()) {
			Player player = playerIter.next();
			
			// remove from past cell
			map.setPlayer(locations.remove(player.getName()), null);
		}
		
		// commit the new move, grabbing the missile pack if applicable
		playerIter = movedTanks.iterator();
		while (playerIter.hasNext()) {
			Player player = playerIter.next();
			// put in new cell
			java.awt.Point location = newLocations.get(player.getName());
			locations.put(player.getName(), location);
			map.setPlayer(location, player);
			
			// get missile pack
			ArrayList<CellObject> missilePacks = map.getAllWithProperty(location, Names.kPropertyMissiles);
			if (missilePacks.size() > 0) {
				assert missilePacks.size() == 1;
				CellObject pack = missilePacks.get(0);
				pack.apply(player);
				map.removeAllWithProperty(location, Names.kPropertyMissiles);
			}
			
			
			// is there a missile in the cell?
			ArrayList<CellObject> missiles = map.getAllWithProperty(location, Names.kPropertyMissile);
			if (missiles.size() == 0) {
				// No, can't collide
				continue;
			}

			// are any flying toward me?
			Iterator<CellObject> iter = missiles.iterator();
			MoveInfo move = lastMoves.get(player.getName());
			while (iter.hasNext()) {
				CellObject missile = iter.next();
				if (move.moveDirection == Direction.backwardOf[missile.getIntProperty(Names.kPropertyDirection)]) {
					missileHit(player, location, missile);
					map.removeObject(location, missile.getName());
					destroyMissile(missile.getName());

					// explosion
					map.setExplosion(location);
				}
			}
		}
		
		// move missiles to new cells, checking for new victims
		updateObjects();
		
		// If there is more than one player out there, keep track of how
		// many updates go by before resetting everything to prevent oscillations
		if (players.size() > 1) {
			missileReset += 1;
		}
		
		if (firedTanks.size() > 0) {
			// at least one player has fired a missile, reset the 
			// missle reset counter to zero
			missileReset = 0;
		}
		
		// Spawn new Missiles in front of Tanks
		playerIter = firedTanks.iterator();
		while (playerIter.hasNext()) {
			Player player = playerIter.next();
			java.awt.Point missileLoc = new java.awt.Point(locations.get(player.getName()));
			
			int direction = player.getFacingInt();
			Direction.translate(missileLoc, direction);
			
			if (!map.isInBounds(missileLoc)) {
				continue;
			}
			if (!map.enterable(missileLoc)) {
				// explosion
				map.setExplosion(missileLoc);
				continue;
			}
			
			CellObject missile = map.createRandomObjectWithProperty(Names.kPropertyMissile);
			missile.setName(player.getName() + "-" + missileID++);
			missile.addProperty(Names.kPropertyDirection, Integer.toString(direction));
			missile.addProperty(Names.kPropertyFlyPhase, "0");
			missile.addProperty(Names.kPropertyOwner, player.getName());
			
			// If there is a tank there, it is hit
			Player other = map.getPlayer(missileLoc);
			if (other != null) {
				missileHit(other, missileLoc, missile);
				
				// explosion
				map.setExplosion(missileLoc);
				
			} else {
				MissileInfo missileInfo = new MissileInfo();
				missileInfo.direction = direction;
				missileInfo.location = new java.awt.Point(missileLoc);
				missiles.put(missile.getName(), missileInfo);

				map.addObjectToCell(missileLoc, missile);
			}
		}
		
		// Handle incoming sensors now that all missiles are flying
		handleIncoming();
		
		// Spawn missile packs
		if (map.numberMissilePacks() < Soar2D.config.kMaxMissilePacks) {
			spawnMissilePack(false);
		}
		
		// Respawn killed Tanks in safe squares
		Iterator<String> playerNameIter = killedTanks.keySet().iterator();
		while (playerNameIter.hasNext()) {
			
			// apply points
			String playerName = playerNameIter.next();
			Player player = playersMap.get(playerName);
			
			player.adjustPoints(Soar2D.config.kKillPenalty, "fragged");
			assert killedTanks.containsKey(playerName);
			playerIter = killedTanks.get(playerName).iterator();
			while (playerIter.hasNext()) {
				Player assailant = playerIter.next();
				if (assailant.getName().equals(player.getName())) {
					continue;
				}
				assailant.adjustPoints(Soar2D.config.kKillAward, "fragged " + player.getName());
			}
			
			fragPlayer(player);
		}
		
		// if the missile reset counter is 100 and there were no killed tanks
		// this turn, reset all tanks
		if ((missileReset >= Soar2D.config.missileResetThreshold) && (killedTanks.size() == 0)) {
			logger.info("missile reset threshold exceeded, resetting all tanks");
			missileReset = 0;
			playerIter = players.iterator();
			while (playerIter.hasNext()) {
				Player player = playerIter.next();
				
				fragPlayer(player);
			}
		}
		
		// Update tanks
		updatePlayers(false);
	}
	
	private void fragPlayer(Player player) {
		// remove from past cell
		java.awt.Point oldLocation = locations.remove(player.getName());
		map.setExplosion(oldLocation);
		map.setPlayer(oldLocation, null);

		// Get available spots
		ArrayList<java.awt.Point> spots = getAvailableLocations(map);
		assert spots.size() > 0;
		
		// pick one and put the player in it
		java.awt.Point location = spots.get(Simulation.random.nextInt(spots.size()));
		map.setPlayer(location, player);
		
		// save the location
		locations.put(player.getName(), location);
		
		// reset the player state
		player.fragged();

		logger.info(player.getName() + ": Spawning at (" + 
				location.x + "," + location.y + ")");
	}
	
	private void handleIncoming() {
		// TODO: a couple of optimizations possible here
		// like marking cells that have been checked, depends on direction though
		// probably more work than it is worth as this should only be slow when there are
		// a ton of missiles flying
		
		Iterator<MissileInfo> iter = missiles.values().iterator();
		while (iter.hasNext()) {
			MissileInfo missile = iter.next();
			java.awt.Point newLocation = new java.awt.Point(missile.location);
			while (true) {
				Direction.translate(newLocation, missile.direction);
				if (!map.enterable(newLocation)) {
					break;
				}
				Player player = map.getPlayer(newLocation);
				if (player != null) {
					player.setIncoming(Direction.backwardOf[missile.direction]);
					break;
				}
			}
		}
	}
	
	private void handleRadarEnergy(Player player) {
		int available = player.getEnergy();
		if (available < player.getRadarPower()) {
			if (available > 0) {
				if (Soar2D.logger.isLoggable(Level.FINER)) logger.finer(player.getName() + ": reducing radar power due to energy shortage");
				player.setRadarPower(available);
			} else {
				if (Soar2D.logger.isLoggable(Level.FINER)) logger.finer(player.getName() + ": radar switched off due to energy shortage");
				player.setRadarSwitch(false);
				return;
			}
		}
		player.adjustEnergy(player.getRadarPower() * -1, "radar");
	}
	
	private void spawnMissilePack(boolean force) {
		if (force || (Simulation.random.nextInt(100) < Soar2D.config.kMissilePackRespawnChance)) {
			// Get available spots
			ArrayList<java.awt.Point> spots = getAvailableLocations(map);
			assert spots.size() > 0;
			
			// Add a missile pack to a spot
			java.awt.Point spot = spots.get(Simulation.random.nextInt(spots.size()));
			logger.info("spawning missile pack at (" + spot.x + "," + spot.y + ")");
			boolean ret = map.addRandomObjectWithProperty(spot, Names.kPropertyMissiles);
			assert ret;
		}
	}
	
	private void chargeUp(Player player, java.awt.Point location) {
		// Charge up
		ArrayList<CellObject> chargers = map.getAllWithProperty(location, Names.kPropertyCharger);
		Iterator<CellObject> iter = chargers.iterator();
		while (iter.hasNext()) {
			CellObject charger = iter.next();
			if (charger.hasProperty(Names.kPropertyHealth)) {
				player.setOnHealthCharger(true);
				if (player.getHealth() < Soar2D.config.kDefaultHealth) {
					player.adjustHealth(charger.getIntProperty(Names.kPropertyHealth), "charger");
				}
			}
			if (charger.hasProperty(Names.kPropertyEnergy)) {
				player.setOnEnergyCharger(true);
				if (player.getEnergy() < Soar2D.config.kDefaultEnergy) {
					player.adjustEnergy(charger.getIntProperty(Names.kPropertyEnergy), "charger");
				}
			}
		}
	}
	
	private void doMoveCollisions(Player player, MoveInfo playerMove, 
			HashMap<String, java.awt.Point> newLocations, 
			HashMap<java.awt.Point, ArrayList<Player> > collisionMap, 
			ArrayList<Player> movedTanks) {
		
		// Get destination location
		java.awt.Point newLocation = newLocations.get(player.getName());
		
		// Wall collisions checked for earlier
		
		// is there a collision in the cell
		ArrayList<Player> collision = collisionMap.get(newLocation);
		if (collision != null) {
			
			// there is a collision

			// if there is only one player here, cancel its move
			if (collision.size() == 1) {
				Player other = collision.get(0);
				MoveInfo otherMove = lastMoves.get(other.getName());
				if (otherMove.move) {
					cancelMove(other, otherMove, newLocations, movedTanks);
					doMoveCollisions(other, otherMove, newLocations, collisionMap, movedTanks);
				}
			} 

			// If there is more than one guy here, they've already been cancelled
			
			
			// Add ourselves to this cell's collision list
			collision.add(player);
			collisionMap.put(newLocation, collision);
			
			// cancel my move
			if (playerMove.move) {
				cancelMove(player, playerMove, newLocations, movedTanks);
				doMoveCollisions(player, playerMove, newLocations, collisionMap, movedTanks);
			}
			return;

		}

		// There is nothing in this cell, create a new list and add ourselves
		collision = new ArrayList<Player>(4);
		collision.add(player);
		collisionMap.put(newLocation, collision);
	}
	
	private void cancelMove(Player player, MoveInfo move, 
			HashMap<String, java.awt.Point> newLocations, 
			ArrayList<Player> movedTanks) {
		move.move = false;
		movedTanks.remove(player);
		newLocations.put(player.getName(), locations.get(player.getName()));
	}
	
	private MoveInfo getAndStoreMove(Player player) {
		MoveInfo move = player.getMove();
		if (Soar2D.control.isShuttingDown()) {
			return null;
		}
		assert move != null;
		String moveString = move.toString();
		if (moveString.length() > 0) {
			logger.info(player.getName() + ": " + moveString);
		}
		lastMoves.put(player.getName(), move);
		
		if (move.stopSim) {
			if (Soar2D.config.terminalAgentCommand) {
				stopAndDumpStats(player.getName() + " issued simulation stop command.", getSortedScores());
			} else {
				Soar2D.logger.warning(player.getName() + " issued ignored stop command.");
			}
		}
		return move;
	}
		
	private void getTankMoves() {
		Iterator<Player> iter = players.iterator();
		while (iter.hasNext()) {
			getAndStoreMove(iter.next());
		}
	}

	private void updateObjects() {
		map.updateObjects(this);
	}

	private void handleCollisions() {
		// Make sure collisions are possible
		if (players.size() < 2) {
			return;
		}
		
		// Optimization to not check the same name twice
		HashSet<Player> colliding = new HashSet<Player>(players.size());
		ArrayList<Player> collision = new ArrayList<Player>(players.size());
		ArrayList<ArrayList<Player>> collisions = new ArrayList<ArrayList<Player>>(players.size() / 2);

		ListIterator<Player> leftIter = players.listIterator();
		while (leftIter.hasNext()) {
			Player left = leftIter.next();
			
			// Check to see if we're already colliding
			if (colliding.contains(left)) {
				continue;
			}
			
			ListIterator<Player> rightIter = players.listIterator(leftIter.nextIndex());
			// Clear collision list now
			collision.clear();
			while (rightIter.hasNext()) {
				// For each player to my right (I've already checked to my left)
				Player right = rightIter.next();

				// Check to see if we're already colliding
				if (colliding.contains(right)) {
					continue;
				}
				
				// If the locations match, we have a collision
				if (locations.get(left.getName()).equals(locations.get(right.getName()))) {
					
					// Add to this set to avoid checking same player again
					colliding.add(left);
					colliding.add(right);
					
					// Add the left the first time a collision is detected
					if (collision.size() == 0) {
						collision.add(left);
						
						// Add the boom on the map
						map.setExplosion(locations.get(left.getName()));
						
						if (logger.isLoggable(Level.FINER)) logger.finer("collision at " + locations.get(left.getName()));
					}
					// Add each right as it is detected
					collision.add(right);
				}
			}
			
			// Add the collision to the total collisions if there is one
			if (collision.size() > 0) {
				collisions.add(new ArrayList<Player>(collision));
			}
		}
		
		// if there are no total collisions, we're done
		if (collisions.size() < 1) {
			return;
		}
		
		Iterator<ArrayList<Player>> collisionIter = collisions.iterator();
		while (collisionIter.hasNext()) {
			collision = collisionIter.next();

			assert collision.size() > 0;
			if (logger.isLoggable(Level.FINER)) logger.finer("Processing collision group with " + collision.size() + " collidees.");

			// Redistribute wealth
			int cash = 0;			
			ListIterator<Player> collideeIter = collision.listIterator();
			while (collideeIter.hasNext()) {
				cash += collideeIter.next().getPoints();
			}
			if (cash > 0) {
				int trash = cash % collision.size();
				cash /= collision.size();
				if (logger.isLoggable(Level.FINER)) logger.finer("Cash to each: " + cash + " (" + trash + " lost in division)");
				collideeIter = collision.listIterator();
				while (collideeIter.hasNext()) {
					collideeIter.next().setPoints(cash, "collision");
				}
			} else {
				if (logger.isLoggable(Level.FINER)) logger.finer("Sum of cash is negative.");
			}
			
			// Remove from former location (only one of these for all players)
			map.setPlayer(locations.get(collision.get(0).getName()), null);
			
			// Move to new cell, consume food
			collideeIter = collision.listIterator();
			while (collideeIter.hasNext()) {
				Player player = collideeIter.next();
				java.awt.Point location = putInStartingLocation(player);
				player.fragged();
				assert location != null;
				if (!lastMoves.get(player.getName()).dontEat) {
					eat(player, location);
				}
			}
		}
	}
	
	public void reset() {
		worldCount = 0;
		printedStats = false;
		missileID = 0;
		missileReset = 0;
		missiles.clear();
	}
	
	public int getWorldCount() {
		return worldCount;
	}
	
	public boolean hasPlayers() {
		return players.size() > 0;
	}

	public Point getLocation(Player player) {
		return locations.get(player.getName());
	}

	public ArrayList<Player> getPlayers() {
		return players;
	}
	
	boolean isTerminal() {
		return printedStats;
	}

	public void destroyMissile(String name) {
		missiles.remove(name);
	}

	public boolean recentlyMovedOrRotated(Player targetPlayer) {
		MoveInfo move = lastMoves.get(targetPlayer.getName());
		if (move == null) {
			return false;
		}
		return move.move || move.rotate;
	}

	public Player getPlayer(String playerName) {
		return playersMap.get(playerName);
	}
}
