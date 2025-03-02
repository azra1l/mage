package mage.player.ai;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.SpellAbility;
import mage.abilities.StaticAbility;
import mage.abilities.common.PassAbility;
import mage.abilities.effects.Effect;
import mage.abilities.effects.SearchEffect;
import mage.abilities.keyword.*;
import mage.cards.Card;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.AbilityType;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.counters.CounterType;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.combat.Combat;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.player.ai.ma.optimizers.TreeOptimizer;
import mage.player.ai.ma.optimizers.impl.DiscardCardOptimizer;
import mage.player.ai.ma.optimizers.impl.EquipOptimizer;
import mage.player.ai.ma.optimizers.impl.LevelUpOptimizer;
import mage.player.ai.ma.optimizers.impl.OutcomeOptimizer;
import mage.player.ai.util.CombatInfo;
import mage.player.ai.util.CombatUtil;
import mage.players.Player;
import mage.target.Target;
import mage.target.TargetAmount;
import mage.target.TargetCard;
import mage.target.Targets;
import mage.util.RandomUtil;
import org.apache.log4j.Logger;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author nantuko
 */
public class ComputerPlayer6 extends ComputerPlayer /*implements Player*/ {

    private static final Logger logger = Logger.getLogger(ComputerPlayer6.class);
    private static final ExecutorService pool = Executors.newFixedThreadPool(1);
    protected int maxDepth;
    protected int maxNodes;
    protected int maxThink;
    protected LinkedList<Ability> actions = new LinkedList<>();
    protected List<UUID> targets = new ArrayList<>();
    protected List<String> choices = new ArrayList<>();
    protected Combat combat;
    protected int currentScore;
    protected SimulationNode2 root;
    List<Permanent> attackersList = new ArrayList<>();
    List<Permanent> attackersToCheck = new ArrayList<>();

    private static final boolean AI_SUGGEST_BY_FILE_ENABLE = true; // old method to instruct AI to play cards (use cheat commands instead now)
    private static final String AI_SUGGEST_BY_FILE_SOURCE = "config/ai.please.cast.this.txt";
    private final List<String> suggestedActions = new ArrayList<>();

    protected Set<String> actionCache;
    private static final List<TreeOptimizer> optimizers = new ArrayList<>();
    protected int lastLoggedTurn = 0;
    protected static final String BLANKS = "...............................................";

    static {
        optimizers.add(new LevelUpOptimizer());
        optimizers.add(new EquipOptimizer());
        optimizers.add(new DiscardCardOptimizer());
        optimizers.add(new OutcomeOptimizer());
    }

    public ComputerPlayer6(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        if (skill < 4) {
            maxDepth = 4;
        } else {
            maxDepth = skill;
        }
        maxThink = skill * 3;
        maxNodes = Config2.maxNodes;
        getSuggestedActions();
        this.actionCache = new HashSet<>();
    }

    public ComputerPlayer6(final ComputerPlayer6 player) {
        super(player);
        this.maxDepth = player.maxDepth;
        this.currentScore = player.currentScore;
        if (player.combat != null) {
            this.combat = player.combat.copy();
        }
        this.actions.addAll(player.actions);
        this.targets.addAll(player.targets);
        this.choices.addAll(player.choices);
        this.actionCache = player.actionCache;
    }

    @Override
    public ComputerPlayer6 copy() {
        return new ComputerPlayer6(this);
    }

    protected void printOutState(Game game) {
        if (logger.isInfoEnabled()) {
            printOutState(game, playerId);
            for (UUID opponentId : game.getOpponents(playerId)) {
                printOutState(game, opponentId);
            }
        }
    }

    protected void printOutState(Game game, UUID playerId) {
        if (lastLoggedTurn != game.getTurnNum()) {
            lastLoggedTurn = game.getTurnNum();
            logger.info(new StringBuilder("------------------------ ").append("Turn: ").append(game.getTurnNum()).append("] --------------------------------------------------------------").toString());
        }

        Player player = game.getPlayer(playerId);
        GameStateEvaluator2.PlayerEvaluateScore score = GameStateEvaluator2.evaluate(playerId, game);
        logger.info(new StringBuilder("[").append(game.getPlayer(playerId).getName()).append("]")
                .append(", life = ").append(player.getLife())
                .append(", score = ").append(score.getTotalScore())
                .append(" (").append(score.getPlayerInfoFull()).append(")")
                .toString());
        StringBuilder sb = new StringBuilder("-> Hand: [");
        for (Card card : player.getHand().getCards(game)) {
            sb.append(card.getName()).append(';');
        }
        logger.info(sb.append(']').toString());
        sb.setLength(0);
        sb.append("-> Permanents: [");
        for (Permanent permanent : game.getBattlefield().getAllPermanents()) {
            if (permanent.isOwnedBy(player.getId())) {
                sb.append(permanent.getName());
                if (permanent.isTapped()) {
                    sb.append("(tapped)");
                }
                if (permanent.isAttacking()) {
                    sb.append("(attacking)");
                }
                sb.append(':' + String.valueOf(GameStateEvaluator2.evaluatePermanent(permanent, game)));
                sb.append(';');
            }
        }
        logger.info(sb.append(']').toString());
    }

    protected void act(Game game) {
        if (actions == null
                || actions.isEmpty()) {
            pass(game);
        } else {
            boolean usedStack = false;
            while (actions.peek() != null) {
                Ability ability = actions.poll();
                // example: ===> SELECTED ACTION for PlayerA: Play Swamp
                logger.info(String.format("===> SELECTED ACTION for %s: %s",
                        getName(),
                        ability.toString()
                                + listTargets(game, ability.getTargets(), " (targeting %s)", "")
                ));
                if (!ability.getTargets().isEmpty()) {
                    for (Target target : ability.getTargets()) {
                        for (UUID id : target.getTargets()) {
                            target.updateTarget(id, game);
                            if (!target.isNotTarget()) {
                                game.addSimultaneousEvent(GameEvent.getEvent(GameEvent.EventType.TARGETED, id, ability, ability.getControllerId()));
                            }
                        }
                    }
                    Player player = game.getPlayer(ability.getFirstTarget());
                    if (player != null) {
                        logger.info("targets = " + player.getName());
                    }
                }
                this.activateAbility((ActivatedAbility) ability, game);
                if (ability.isUsesStack()) {
                    usedStack = true;
                }
                if (!suggestedActions.isEmpty() && !(ability instanceof PassAbility)) {
                    Iterator<String> it = suggestedActions.iterator();
                    while (it.hasNext()) {
                        String action = it.next();
                        Card card = game.getCard(ability.getSourceId());
                        if (card != null && action.equals(card.getName())) {
                            logger.info("-> removed from suggested=" + action);
                            it.remove();
                        }
                    }
                }
            }
            if (usedStack) {
                pass(game);
            }
        }
    }

    protected int addActions(SimulationNode2 node, int depth, int alpha, int beta) {
        boolean stepFinished = false;
        int val;
        if (logger.isTraceEnabled()
                && node != null
                && node.getAbilities() != null
                && !node.getAbilities().toString().equals("[Pass]")) {
            logger.trace("Add Action [" + depth + "] " + node.getAbilities().toString() + "  a: " + alpha + " b: " + beta);
        }
        Game game = node.getGame();
        if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS
                && Thread.interrupted()) {
            Thread.currentThread().interrupt();
            logger.debug("interrupted");
            return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
        }
        // Condition to stop deeper simulation
        if (depth <= 0
                || SimulationNode2.nodeCount > maxNodes
                || game.checkIfGameIsOver()) {
            val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
            if (logger.isTraceEnabled()) {
                StringBuilder sb = new StringBuilder("Add Actions -- reached end state  <").append(val).append('>');
                SimulationNode2 logNode = node;
                do {
                    sb.append(new StringBuilder(" <- [" + logNode.getDepth() + ']' + (logNode.getAbilities() != null ? logNode.getAbilities().toString() : "[empty]")));
                    logNode = logNode.getParent();
                } while ((logNode.getParent() != null));
                logger.trace(sb);
            }
        } else if (!node.getChildren().isEmpty()) {
            if (logger.isDebugEnabled()) {
                StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                        .append("] -- something added children ")
                        .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                        .append(" added children: ").append(node.getChildren().size()).append(" (");
                for (SimulationNode2 logNode : node.getChildren()) {
                    sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                }
                sb.append(')');
                logger.debug(sb);
            }
            val = minimaxAB(node, depth - 1, alpha, beta);
        } else {
            logger.trace("Add Action -- alpha: " + alpha + " beta: " + beta + " depth:" + depth + " step:" + game.getTurn().getStepType() + " for player:" + game.getPlayer(game.getActivePlayerId()).getName());
            if (allPassed(game)) {
                if (!game.getStack().isEmpty()) {
                    resolve(node, depth, game);
                } else {
                    stepFinished = true;
                }
            }

            if (game.checkIfGameIsOver()) {
                val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
            } else if (stepFinished) {
                logger.debug("Step finished");
                int testScore = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
                if (game.isActivePlayer(playerId)) {
                    if (testScore < currentScore) {
                        // if score at end of step is worse than original score don't check further
                        //logger.debug("Add Action -- abandoning check, no immediate benefit");
                        val = testScore;
                    } else {
                        val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
                    }
                } else {
                    val = GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
                }
            } else if (!node.getChildren().isEmpty()) {
                if (logger.isDebugEnabled()) {
                    StringBuilder sb = new StringBuilder("Add Action [").append(depth)
                            .append("] -- trigger ")
                            .append(node.getAbilities() != null ? node.getAbilities().toString() : "null")
                            .append(" added children: ").append(node.getChildren().size()).append(" (");
                    for (SimulationNode2 logNode : node.getChildren()) {
                        sb.append(logNode.getAbilities() != null ? logNode.getAbilities().toString() : "null").append(", ");
                    }
                    sb.append(')');
                    logger.debug(sb);
                }
                val = minimaxAB(node, depth - 1, alpha, beta);
            } else {
                val = simulatePriority(node, game, depth, alpha, beta);
            }
        }
        node.setScore(val);
        logger.trace("returning -- score: " + val + " depth:" + depth + " step:" + game.getTurn().getStepType() + " for player:" + game.getPlayer(node.getPlayerId()).getName());
        return val;

    }

    protected boolean getNextAction(Game game) {
        if (root != null
                && !root.children.isEmpty()) {
            SimulationNode2 test = root;
            root = root.children.get(0);
            while (!root.children.isEmpty()
                    && !root.playerId.equals(playerId)) {
                test = root;
                root = root.children.get(0);
            }
            logger.trace("Sim getNextAction -- game value:" + game.getState().getValue(true) + " test value:" + test.gameValue);
            if (!suggestedActions.isEmpty()) {
                return false;
            }
            if (root.playerId.equals(playerId)
                    && root.abilities != null
                    && game.getState().getValue(true).hashCode() == test.gameValue) {
                logger.info("simulating -- continuing previous action chain");
                actions = new LinkedList<>(root.abilities);
                combat = root.combat;
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    protected int minimaxAB(SimulationNode2 node, int depth, int alpha, int beta) {
        logger.trace("Sim minimaxAB [" + depth + "] -- a: " + alpha + " b: " + beta + " <" + (node != null ? node.getScore() : "null") + '>');
        UUID currentPlayerId = node.getGame().getPlayerList().get();
        SimulationNode2 bestChild = null;
        for (SimulationNode2 child : node.getChildren()) {
            Combat _combat = child.getCombat();
            if (alpha >= beta) {
                break;
            }
            if (SimulationNode2.nodeCount > maxNodes) {
                break;
            }
            int val = addActions(child, depth - 1, alpha, beta);
            if (!currentPlayerId.equals(playerId)) {
                if (val < beta) {
                    beta = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.LOSE_GAME_SCORE) {
                    logger.debug("lose - break");
                    break;
                }
            } else {
                if (val > alpha) {
                    alpha = val;
                    bestChild = child;
                    if (node.getCombat() == null) {
                        node.setCombat(_combat);
                        bestChild.setCombat(_combat);
                    }
                }
                // no need to check other actions
                if (val == GameStateEvaluator2.WIN_GAME_SCORE) {
                    logger.debug("win - break");
                    break;
                }
            }
        }
        node.children.clear();
        if (bestChild != null) {
            node.children.add(bestChild);
        }
        if (!currentPlayerId.equals(playerId)) {
            return beta;
        } else {
            return alpha;
        }
    }

    protected SearchEffect getSearchEffect(StackAbility ability) {
        for (Effect effect : ability.getEffects()) {
            if (effect instanceof SearchEffect) {
                return (SearchEffect) effect;
            }
        }
        return null;
    }

    protected void resolve(SimulationNode2 node, int depth, Game game) {
        StackObject stackObject = game.getStack().getFirst();
        if (stackObject instanceof StackAbility) {
            // AI hint for search effects (calc all possible cards for best score)
            SearchEffect effect = getSearchEffect((StackAbility) stackObject);
            if (effect != null
                    && stackObject.getControllerId().equals(playerId)) {
                Target target = effect.getTarget();
                if (!target.doneChoosing()) {
                    for (UUID targetId : target.possibleTargets(stackObject.getControllerId(), stackObject.getStackAbility(), game)) {
                        Game sim = game.copy();
                        StackAbility newAbility = (StackAbility) stackObject.copy();
                        SearchEffect newEffect = getSearchEffect(newAbility);
                        newEffect.getTarget().addTarget(targetId, newAbility, sim);
                        sim.getStack().push(newAbility);
                        SimulationNode2 newNode = new SimulationNode2(node, sim, depth, stackObject.getControllerId());
                        node.children.add(newNode);
                        newNode.getTargets().add(targetId);
                        logger.trace("Sim search -- node#: " + SimulationNode2.getCount() + " for player: " + sim.getPlayer(stackObject.getControllerId()).getName());
                    }
                    return;
                }
            }
        }
        stackObject.resolve(game);
        if (stackObject instanceof StackAbility) {
            game.getStack().remove(stackObject, game);
        }
        game.applyEffects();
        game.getPlayers().resetPassed();
        game.getPlayerList().setCurrent(game.getActivePlayerId());
    }

    /**
     * Base call for simulation of AI actions
     *
     * @return
     */
    protected Integer addActionsTimed() {
        FutureTask<Integer> task = new FutureTask<>(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return addActions(root, maxDepth, Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
        });
        pool.execute(task);
        try {
            int maxSeconds = maxThink;
            if (COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS) {
                maxSeconds = 3600;
            }
            logger.debug("maxThink: " + maxSeconds + " seconds ");
            if (task.get(maxSeconds, TimeUnit.SECONDS) != null) {
                return task.get(maxSeconds, TimeUnit.SECONDS);
            }
        } catch (TimeoutException e) {
            logger.info("simulating - timed out");
            task.cancel(true);
        } catch (ExecutionException e) {
            // exception error in simulated game
            e.printStackTrace();
            task.cancel(true);
            // real games: must catch
            // unit tests: must raise again for test fail
            if (this.isTestsMode()) {
                throw new IllegalStateException("One of the simulated games raise the error: " + e.getCause());
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            task.cancel(true);
        } catch (Exception e) {
            e.printStackTrace();
            task.cancel(true);
        }
        //TODO: timeout handling
        return 0;
    }

    protected int simulatePriority(SimulationNode2 node, Game game, int depth, int alpha, int beta) {
        if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS
                && Thread.interrupted()) {
            Thread.currentThread().interrupt();
            logger.info("interrupted");
            return GameStateEvaluator2.evaluate(playerId, game).getTotalScore();
        }
        node.setGameValue(game.getState().getValue(true).hashCode());
        SimulatedPlayer2 currentPlayer = (SimulatedPlayer2) game.getPlayer(game.getPlayerList().get());
        SimulationNode2 bestNode = null;
        List<Ability> allActions = currentPlayer.simulatePriority(game);
        optimize(game, allActions);
        int startedScore = GameStateEvaluator2.evaluate(this.getId(), node.getGame()).getTotalScore();
        if (logger.isInfoEnabled()
                && !allActions.isEmpty()
                && depth == maxDepth) {
            logger.info(String.format("POSSIBLE ACTIONS for %s (%d, started score: %d)%s",
                    getName(),
                    allActions.size(),
                    startedScore,
                    (actions.isEmpty() ? "" : ":")
            ));
            for (int i = 0; i < allActions.size(); i++) {
                logger.info(String.format("-> #%d (%s)", i + 1, allActions.get(i)));
            }
        }
        int actionNumber = 0;
        int bestValSubNodes = Integer.MIN_VALUE;
        for (Ability action : allActions) {
            actionNumber++;
            if (!COMPUTER_DISABLE_TIMEOUT_IN_GAME_SIMULATIONS
                    && Thread.interrupted()) {
                Thread.currentThread().interrupt();
                logger.info("Sim Prio [" + depth + "] -- interrupted");
                break;
            }
            Game sim = game.copy();
            sim.setSimulation(true);
            if (!(action instanceof StaticAbility) //for MorphAbility, etc
                    && sim.getPlayer(currentPlayer.getId()).activateAbility((ActivatedAbility) action.copy(), sim)) {
                sim.applyEffects();
                if (checkForRepeatedAction(sim, node, action, currentPlayer.getId())) {
                    logger.debug("Sim Prio [" + depth + "] -- repeated action: " + action);
                    continue;
                }
                if (!sim.checkIfGameIsOver()
                        && (action.isUsesStack() || action instanceof PassAbility)) {
                    // skip priority for opponents before stack resolve
                    UUID nextPlayerId = sim.getPlayerList().get();
                    do {
                        sim.getPlayer(nextPlayerId).pass(game);
                        nextPlayerId = sim.getPlayerList().getNext();
                    } while (!Objects.equals(nextPlayerId, this.getId()));
                }
                SimulationNode2 newNode = new SimulationNode2(node, sim, action, depth, currentPlayer.getId());
                sim.checkStateAndTriggered();
                int actionScore;
                if (action instanceof PassAbility && sim.getStack().isEmpty()) {
                    // no more next actions, it's a final score
                    actionScore = GameStateEvaluator2.evaluate(this.getId(), sim).getTotalScore();
                } else {
                    // resolve current action and calc all next actions to find best score (return max possible score)
                    actionScore = addActions(newNode, depth - 1, alpha, beta);
                }
                logger.debug("Sim Prio " + BLANKS.substring(0, 2 + (maxDepth - depth) * 3) + '[' + depth + "]#" + actionNumber + " <" + actionScore + "> - (" + action + ") ");

                // Hints on data:
                // * node - started game with executed command (pay and put on stack)
                // * newNode - resolved game with resolved command (resolve stack)
                // * node.children - rewrites to store only best tree (e.g. contains only final data)
                // * node.score - rewrites to store max score (e.g. contains only final data)
                if (logger.isInfoEnabled()
                        && depth >= maxDepth) {
                    // show calculated actions and score
                    // example: Sim Prio [6] #1 <605> (Play Swamp)
                    int currentActionScore = GameStateEvaluator2.evaluate(this.getId(), newNode.getGame()).getTotalScore();
                    int diffCurrentAction = currentActionScore - startedScore;
                    int diffNextActions = actionScore - startedScore - diffCurrentAction;
                    logger.info(String.format("Sim Prio [%d] #%d <diff %s, %s> (%s)",
                            depth,
                            actionNumber,
                            printDiffScore(diffCurrentAction),
                            printDiffScore(diffNextActions),
                            action
                                    + (action.isModal() ? " Mode = " + action.getModes().getMode().toString() : "")
                                    + listTargets(game, action.getTargets(), " (targeting %s)", "")
                                    + (logger.isTraceEnabled() ? " #" + newNode.hashCode() : "")
                    ));
                    // collect childs info (next actions chain)
                    SimulationNode2 logNode = newNode;
                    while (logNode.getChildren() != null
                            && !logNode.getChildren().isEmpty()) {
                        logNode = logNode.getChildren().get(0);
                        if (logNode.getAbilities() != null
                                && !logNode.getAbilities().isEmpty()) {
                            int logCurrentScore = GameStateEvaluator2.evaluate(this.getId(), logNode.getGame()).getTotalScore();
                            int logPrevScore = GameStateEvaluator2.evaluate(this.getId(), logNode.getParent().getGame()).getTotalScore();
                            logger.info(String.format("Sim Prio [%d] -> next action: [%d]%s <diff %s, %s>",
                                    depth,
                                    logNode.getDepth(),
                                    logNode.getAbilities().toString(),
                                    printDiffScore(logCurrentScore - logPrevScore),
                                    printDiffScore(actionScore - logCurrentScore)
                            ));
                        }
                    }
                }

                if (currentPlayer.getId().equals(playerId)) {
                    if (actionScore > bestValSubNodes) {
                        bestValSubNodes = actionScore;
                    }
                    if (depth == maxDepth
                            && action instanceof PassAbility) {
                        actionScore = actionScore - PASSIVITY_PENALTY; // passivity penalty
                    }
                    if (actionScore > alpha
                            || (depth == maxDepth
                            && actionScore == alpha
                            && RandomUtil.nextBoolean())) { // Adding random for equal value to get change sometimes
                        alpha = actionScore;
                        bestNode = newNode;
                        bestNode.setScore(actionScore);
                        if (!newNode.getChildren().isEmpty()) {
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }
                        if (depth == maxDepth) {
                            GameStateEvaluator2.PlayerEvaluateScore score = GameStateEvaluator2.evaluate(this.getId(), bestNode.game);
                            String scoreInfo = " [" + score.getPlayerInfoShort() + "-" + score.getOpponentInfoShort() + "]";
                            String abilitiesInfo = bestNode.getAbilities()
                                    .stream()
                                    .map(a -> a.toString() + listTargets(game, a.getTargets(), " (targeting %s)", ""))
                                    .collect(Collectors.joining("; "));
                            logger.info("Sim Prio [" + depth + "] >> BEST action chain found <" + bestNode.getScore() + scoreInfo + "> " + abilitiesInfo);
                            node.children.clear();
                            node.children.add(bestNode);
                            node.setScore(bestNode.getScore());
                        }
                    }

                    // no need to check other actions
                    if (actionScore == GameStateEvaluator2.WIN_GAME_SCORE) {
                        logger.debug("Sim Prio -- win - break");
                        break;
                    }
                } else {
                    if (actionScore < beta) {
                        beta = actionScore;
                        bestNode = newNode;
                        bestNode.setScore(actionScore);
                        if (!newNode.getChildren().isEmpty()) {
                            bestNode.setCombat(newNode.getChildren().get(0).getCombat());
                        }
                    }

                    // no need to check other actions
                    if (actionScore == GameStateEvaluator2.LOSE_GAME_SCORE) {
                        logger.debug("Sim Prio -- lose - break");
                        break;
                    }
                }
                if (alpha >= beta) {
                    break;
                }
                if (SimulationNode2.nodeCount > maxNodes) {
                    logger.debug("Sim Prio -- reached end-state");
                    break;
                }
            }
        } // end of for (allActions)

        if (depth == maxDepth) {
            logger.info("Sim Prio [" + depth + "] -- End for Max Depth  -- Nodes calculated: " + SimulationNode2.nodeCount);
        }
        if (bestNode != null) {
            node.children.clear();
            node.children.add(bestNode);
            node.setScore(bestNode.getScore());
            if (logger.isTraceEnabled()
                    && !bestNode.getAbilities().toString().equals("[Pass]")) {
                logger.trace(new StringBuilder("Sim Prio [").append(depth).append("] -- Set after (depth=").append(depth).append(")  <").append(bestNode.getScore()).append("> ").append(bestNode.getAbilities().toString()).toString());
            }
        }

        if (currentPlayer.getId().equals(playerId)) {
            return bestValSubNodes;
        } else {
            return beta;
        }
    }

    private String printDiffScore(int score) {
        if (score >= 0) {
            return "+" + score;
        } else {
            return "" + score;
        }
    }

    /**
     * Various AI optimizations for actions.
     *
     * @param game
     * @param allActions
     */
    protected void optimize(Game game, List<Ability> allActions) {
        for (TreeOptimizer optimizer : optimizers) {
            optimizer.optimize(game, allActions);
        }
        Collections.sort(allActions, new Comparator<Ability>() {
            @Override
            public int compare(Ability ability1, Ability ability2) {
                String rule1 = ability1.toString();
                String rule2 = ability2.toString();

                // pass
                boolean pass1 = rule1.startsWith("Pass");
                boolean pass2 = rule2.startsWith("Pass");
                if (pass1 != pass2) {
                    if (pass1) {
                        return 1;
                    } else {
                        return -1;
                    }
                }

                // play
                boolean play1 = rule1.startsWith("Play");
                boolean play2 = rule2.startsWith("Play");
                if (play1 != play2) {
                    if (play1) {
                        return -1;
                    } else {
                        return 1;
                    }
                }

                // cast
                boolean cast1 = rule1.startsWith("Cast");
                boolean cast2 = rule2.startsWith("Cast");
                if (cast1 != cast2) {
                    if (cast1) {
                        return -1;
                    } else {
                        return 1;
                    }
                }

                // default
                return ability1.getRule().compareTo(ability2.getRule());
            }
        });
    }

    protected boolean allPassed(Game game) {
        for (Player player : game.getPlayers().values()) {
            if (!player.isPassed()
                    && !player.hasLost()
                    && !player.hasLeft()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (choices.isEmpty()) {
            return super.choose(outcome, choice, game);
        }
        if (!choice.isChosen()) {
            if (!choice.setChoiceByAnswers(choices, true)) {
                choice.setRandomChoice();
            }
        }
        return true;
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (targets.isEmpty()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        }
        if (!target.doneChoosing()) {
            for (UUID targetId : targets) {
                target.addTarget(targetId, source, game);
                if (target.doneChoosing()) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Game game) {
        if (targets.isEmpty()) {
            return super.choose(outcome, cards, target, game);
        }
        if (!target.doneChoosing()) {
            for (UUID targetId : targets) {
                target.add(targetId, game);
                if (target.doneChoosing()) {
                    targets.clear();
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void declareBlockers(Game game, UUID activePlayerId) {
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_BLOCKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_BLOCKERS, activePlayerId, activePlayerId))) {
            List<Permanent> attackers = getAttackers(game);
            if (attackers == null) {
                return;
            }

            List<Permanent> possibleBlockers = super.getAvailableBlockers(game);
            possibleBlockers = filterOutNonblocking(game, attackers, possibleBlockers);
            if (possibleBlockers.isEmpty()) {
                return;
            }

            attackers = filterOutUnblockable(game, attackers, possibleBlockers);
            if (attackers.isEmpty()) {
                return;
            }

            CombatUtil.sortByPower(attackers, false);

            CombatInfo combatInfo = CombatUtil.blockWithGoodTrade2(game, attackers, possibleBlockers);
            Player player = game.getPlayer(playerId);

            boolean blocked = false;
            for (Map.Entry<Permanent, List<Permanent>> entry : combatInfo.getCombat().entrySet()) {
                UUID attackerId = entry.getKey().getId();
                List<Permanent> blockers = entry.getValue();
                if (blockers != null) {
                    for (Permanent blocker : blockers) {
                        player.declareBlocker(player.getId(), blocker.getId(), attackerId, game);
                        blocked = true;
                    }
                }
            }
            if (blocked) {
                game.getPlayers().resetPassed();
            }
        }
    }

    private List<Permanent> filterOutNonblocking(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> blockersLeft = new ArrayList<>();
        for (Permanent blocker : blockers) {
            for (Permanent attacker : attackers) {
                if (blocker.canBlock(attacker.getId(), game)) {
                    blockersLeft.add(blocker);
                    break;
                }
            }
        }
        return blockersLeft;
    }

    private List<Permanent> filterOutUnblockable(Game game, List<Permanent> attackers, List<Permanent> blockers) {
        List<Permanent> attackersLeft = new ArrayList<>();
        for (Permanent attacker : attackers) {
            if (CombatUtil.canBeBlocked(game, attacker, blockers)) {
                attackersLeft.add(attacker);
            }
        }
        return attackersLeft;
    }

    private List<Permanent> getAttackers(Game game) {
        List<UUID> attackersUUID = game.getCombat().getAttackers();
        if (attackersUUID.isEmpty()) {
            return null;
        }

        List<Permanent> attackers = new ArrayList<>();
        for (UUID attackerId : attackersUUID) {
            Permanent permanent = game.getPermanent(attackerId);
            attackers.add(permanent);
        }
        return attackers;
    }

    /**
     * Choose attackers based on static information. That means that AI won't
     * look to the future as it was before, but just choose attackers based on
     * current state of the game. This is worse, but at least it is easier to
     * implement and won't lead to the case when AI doesn't do anything -
     * neither attack nor block.
     *
     * @param game
     * @param activePlayerId
     */
    private void declareAttackers(Game game, UUID activePlayerId) {
        attackersToCheck.clear();
        attackersList.clear();
        game.fireEvent(new GameEvent(GameEvent.EventType.DECLARE_ATTACKERS_STEP_PRE, null, null, activePlayerId));
        if (!game.replaceEvent(GameEvent.getEvent(GameEvent.EventType.DECLARING_ATTACKERS, activePlayerId, activePlayerId))) {
            Player attackingPlayer = game.getPlayer(activePlayerId);

            // check alpha strike first (all in attack to kill)
            for (UUID defenderId : game.getOpponents(playerId)) {
                Player defender = game.getPlayer(defenderId);
                if (!defender.isInGame()) {
                    continue;
                }

                attackersList = super.getAvailableAttackers(defenderId, game);
                if (attackersList.isEmpty()) {
                    continue;
                }
                List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);
                List<Permanent> killers = CombatUtil.canKillOpponent(game, attackersList, possibleBlockers, defender);
                if (!killers.isEmpty()) {
                    for (Permanent attacker : killers) {
                        attackingPlayer.declareAttacker(attacker.getId(), defenderId, game, false);
                    }
                    return;
                }
            }

            // check all other actions
            for (UUID defenderId : game.getOpponents(playerId)) {
                Player defender = game.getPlayer(defenderId);
                if (!defender.isInGame()) {
                    continue;
                }
                attackersList = super.getAvailableAttackers(defenderId, game);
                if (attackersList.isEmpty()) {
                    continue;
                }
                List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);

                // The AI will now attack more sanely.  Simple, but good enough for now.
                // The sim minmax does not work at the moment.
                boolean safeToAttack;
                CombatEvaluator eval = new CombatEvaluator();

                for (Permanent attacker : attackersList) {
                    safeToAttack = true;
                    int attackerValue = eval.evaluate(attacker, game);
                    for (Permanent blocker : possibleBlockers) {
                        int blockerValue = eval.evaluate(blocker, game);

                        // blocker can kill attacker
                        if (attacker.getPower().getValue() <= blocker.getToughness().getValue()
                                && attacker.getToughness().getValue() <= blocker.getPower().getValue()) {
                            safeToAttack = false;
                        }

                        // attacker and blocker have the same P/T, check their overall value
                        if (attacker.getToughness().getValue() == blocker.getPower().getValue()
                                && attacker.getPower().getValue() == blocker.getToughness().getValue()) {
                            if (attackerValue > blockerValue
                                    || blocker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                    || blocker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                    || blocker.getAbilities().contains(new ExaltedAbility())
                                    || blocker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                                    || blocker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())
                                    || !attacker.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())
                                    || !attacker.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())
                                    || !attacker.getAbilities().contains(new ExaltedAbility())) {
                                safeToAttack = false;
                            }
                        }

                        // attacker can kill by deathtouch
                        if (attacker.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())
                                || attacker.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())) {
                            safeToAttack = true;
                        }

                        // attacker has flying and blocker has neither flying nor reach
                        if (attacker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                                && !blocker.getAbilities().containsKey(FlyingAbility.getInstance().getId())
                                && !blocker.getAbilities().containsKey(ReachAbility.getInstance().getId())) {
                            safeToAttack = true;
                        }

                        // if any check fails, move on to the next possible attacker
                        if (!safeToAttack) {
                            break;
                        }
                    }

                    // 0 power, don't bother attacking
                    if (attacker.getPower().getValue() == 0) {
                        safeToAttack = false;
                    }

                    // add attacker to the next list of all attackers that can safely attack
                    if (safeToAttack) {
                        attackersToCheck.add(attacker);
                    }
                }

                // now we have a list of all attackers that can safely attack:
                // first check to see if any Planeswalkers can be killed
                int totalPowerOfAttackers = 0;
                int loyaltyCounters = 0;
                for (Permanent planeswalker : game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_PLANESWALKER, defender.getId(), game)) {
                    if (planeswalker != null) {
                        loyaltyCounters = planeswalker.getCounters(game).getCount(CounterType.LOYALTY);
                        // verify the attackers can kill the planeswalker, otherwise attack the player
                        for (Permanent attacker : attackersToCheck) {
                            totalPowerOfAttackers += attacker.getPower().getValue();
                        }
                        if (totalPowerOfAttackers < loyaltyCounters) {
                            break;
                        }
                        // kill the Planeswalker
                        for (Permanent attacker : attackersToCheck) {
                            loyaltyCounters -= attacker.getPower().getValue();
                            attackingPlayer.declareAttacker(attacker.getId(), planeswalker.getId(), game, true);
                            if (loyaltyCounters <= 0) {
                                break;
                            }
                        }
                    }
                }

                // any remaining attackers go for the player
                for (Permanent attackingPermanent : attackersToCheck) {
                    // if not already attacking a Planeswalker...
                    if (!attackingPermanent.isAttacking()) {
                        attackingPlayer.declareAttacker(attackingPermanent.getId(), defenderId, game, true);
                    }
                }
            }
        }
    }

    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        logger.debug("selectAttackers");
        declareAttackers(game, playerId);
    }

    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        logger.debug("selectBlockers");
        declareBlockers(game, playerId);
    }

    /**
     * Copies game and replaces all players in copy with simulated players
     *
     * @param game
     * @return a new game object with simulated players
     */
    protected Game createSimulation(Game game) {
        Game sim = game.copy();
        sim.setSimulation(true);
        for (Player oldPlayer : sim.getState().getPlayers().values()) {
            // replace original player by simulated player and find result (execute/resolve current action)
            Player origPlayer = game.getState().getPlayers().get(oldPlayer.getId()).copy();
            if (!suggestedActions.isEmpty()) {
                logger.debug(origPlayer.getName() + " suggested: " + suggestedActions);
            }
            SimulatedPlayer2 simPlayer = new SimulatedPlayer2(oldPlayer, oldPlayer.getId().equals(playerId), suggestedActions);
            simPlayer.restore(origPlayer);
            sim.getState().getPlayers().put(oldPlayer.getId(), simPlayer);
        }
        return sim;
    }

    private boolean checkForRepeatedAction(Game sim, SimulationNode2 node, Ability action, UUID playerId) {
        // pass or casting two times a spell multiple times on hand is ok
        if (action instanceof PassAbility || action instanceof SpellAbility || action.getAbilityType() == AbilityType.MANA) {
            return false;
        }
        int newVal = GameStateEvaluator2.evaluate(playerId, sim).getTotalScore();
        SimulationNode2 test = node.getParent();
        while (test != null) {
            if (test.getPlayerId().equals(playerId)) {
                if (test.getAbilities() != null && test.getAbilities().size() == 1) {
                    if (action.toString().equals(test.getAbilities().get(0).toString())) {
                        if (test.getParent() != null) {
                            Game prevGame = node.getGame();
                            if (prevGame != null) {
                                int oldVal = GameStateEvaluator2.evaluate(playerId, prevGame).getTotalScore();
                                if (oldVal >= newVal) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            test = test.getParent();
        }
        return false;
    }

    protected final void getSuggestedActions() {
        if (!AI_SUGGEST_BY_FILE_ENABLE) {
            return;
        }
        Scanner scanner = null;
        try {
            File file = new File(AI_SUGGEST_BY_FILE_SOURCE);
            if (file.exists()) {
                scanner = new Scanner(file);
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.startsWith("cast:")
                            || line.startsWith("play:")) {
                        suggestedActions.add(line.substring(5));
                    }
                }
                System.out.println("suggested::");
                for (int i = 0; i < suggestedActions.size(); i++) {
                    System.out.println("    " + suggestedActions.get(i));
                }
            }
        } catch (Exception e) {
            // swallow
            e.printStackTrace();
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
    }

    @Override
    public void addAction(String action) {
        if (action != null
                && (action.startsWith("cast:")
                || action.startsWith("play:"))) {
            suggestedActions.add(action.substring(5));
        }
    }

    @Override
    public int getActionCount() {
        return suggestedActions.size();
    }

    /**
     * Return info about targets list (targeting objects)
     *
     * @param game
     * @param targets
     * @param format    example: my %s in data
     * @param emptyText default text for empty targets list
     * @return
     */
    protected String listTargets(Game game, Targets targets, String format, String emptyText) {
        List<String> res = new ArrayList<>();
        for (Target target : targets) {
            for (UUID id : target.getTargets()) {
                MageObject object = game.getObject(id);
                if (object != null) {
                    String prefix = "";
                    if (target instanceof TargetAmount) {
                        prefix = " " + target.getTargetAmount(id) + "x ";
                    }
                    res.add(prefix + object.getIdName());
                }
            }
        }
        String info = String.join("; ", res);

        if (info.isEmpty()) {
            return emptyText;
        } else {
            return String.format(format, info);
        }
    }

    @Override
    public void cleanUpOnMatchEnd() {
        root = null;
        super.cleanUpOnMatchEnd();
    }

}
