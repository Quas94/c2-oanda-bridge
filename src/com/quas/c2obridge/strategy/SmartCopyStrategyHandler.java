package com.quas.c2obridge.strategy;

import com.quas.c2obridge.C2OBridge;
import com.quas.c2obridge.Logger;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Strategy #2: copy C2 but smartly!
 * - we react to every trade opened by C2 by setting a limit order that is ENTRY_PIPS_DIFF pips negative with regards to C2's trade
 * - stop loss will be at C2_STOPLOSS_PERCENT of C2's trade, starting at our limit order's entry point.
 * - re-entry: completely manual. re-entry rules:
 *   ~ only consider re-entry when C2 loss is 5%+
 *   ~ re-entry position size - 50% of position size multiplier (ie. if multiplier is 6, for re-entry, -> 3 instead)
 *   ~ only re-enter when there is ample evidence of reversal in our favour (eg. support or resistance, other patterns)
 *   ~ once re-entered, increasing position size is only allowed once per -2.5% of C2 account maximum
 *   ~ manually re-entered trades will remain on the blacklist so will not be added to by the auto-trader
 * - keep a list of all currently-opened positions. when C2 sends open notice, check to see if the currency pair is on
 *   the local currently-open list, and the blacklist.
 *   ~ if on blacklist, show notification, do not open trade.
 *   ~ if not on either list, this is a completely fresh trade - proceed to open without any other issues
 *   ~ if on the local currently-open list, check with Oanda to see if the trade is still active
 *   ~ if still active, only add to the position if there's only 1 trade so far for the pair, and if the distance to
 *     stop-loss is more than 10 pips. make the stop-loss the same as the first trade, so the risk is increased, up to
 *     a maximum of 40% risk increase (at this time, 3.6->5.0% total risk). scale the new position size if necessary to
 *     stay within this upper limit of risk.
 *   ~ if no longer active, it means we have been stopped out, so remove the pair from the local currently-open list and
 *     add to blacklist. do not open trade.
 *
 * Stop loss of 0.6% of C2's account has 75% of trades with drawdowns equal to or lower than it.
 * Stop loss of 3.25% of C2's account has 92% of trades with drawdowns equal to or lower than it.
 *
 * Created by Quasar on 4/12/2015.
 */
public class SmartCopyStrategyHandler extends StrategyHandler {

	/** Stop-loss when C2's account hits this PERCENTAGE drawdown */
	private static final double C2_STOPLOSS_PERCENT = 3.33333;

	/** Multiplier applied against C2 position sizing */
	private static final int POS_SIZE_MULTIPLIER = 3;

	/** Percentage of our account risked per trade, equivalent to C2_STOPLOSS_PERCENT * POS_SIZE_MULTIPLIER */
	private static final double ACC_STOPLOSS_PERCENT = C2_STOPLOSS_PERCENT * POS_SIZE_MULTIPLIER;

	/**
	 * Number of pips from C2's original opening price (oprice variable) in OUR FAVOUR to place limit order at.
	 */
	private static final int LIMIT_ORDER_PIPS_DIFF = 10;
	/**
	 * Number of pips that must separate the current price from new stop loss when making an additional trade.
	 */
	private static final int ADD_TRADE_MIN_GAP = 10;

	/** Smart copy file */
	private static final String SMART_COPY_FILE = "smartcopy.properties";
	/** For save properties */
	private static final String CURRENTLY_OPEN = "CURRENTLY_OPEN";
	private static final String BLACKLIST = "BLACKLIST";

	/**
	 * List of currency pairs that are on the local-currently-open list. (Does not mean it is necessarily currently
	 * open on Oanda, as it could have been stopped out)
	 */
	private Set<String> currentlyOpen;

	/** Blacklisted currency pairs: pairs that have been stopped out beyond the 0.6% of C2 account */
	private Set<String> blacklist;

	/**
	 * Constructor for the smart copy strategy.
	 *
	 * @param accountId the account id for the reverse strategy
	 */
	public SmartCopyStrategyHandler(int accountId) {
		super(accountId);

		this.currentlyOpen = new HashSet<String>();
		this.blacklist = new HashSet<String>();

		// load saved data from properties file
		Properties saveData = new Properties();
		try {
			saveData.load(new FileInputStream(new File(SMART_COPY_FILE)));

			String[] currentlyOpenUnvalidated = saveData.getProperty(CURRENTLY_OPEN).split(",");
			String[] blacklistUnvalidated = saveData.getProperty(BLACKLIST).split(",");
			for (String s : currentlyOpenUnvalidated) {
				if (s.equals("")) continue;
				s = s.trim().toUpperCase(); // trim whitespace and uppercase
				validateCurrencyPair(s); // throws RuntimeException if invalid

				// add to currentlyOpen
				currentlyOpen.add(s);
			}
			for (String s : blacklistUnvalidated) {
				if (s.equals("")) continue;
				s = s.trim().toUpperCase();
				validateCurrencyPair(s);

				if (currentlyOpen.contains(s)) {
					throw new RuntimeException("currency pair " + s + " is in both currentlyOpen and blacklist");
				}

				// add to blacklist
				blacklist.add(s);
			}
		} catch (IOException ioe) {
			Logger.error("Error loading smartcopy.properties save data: " + ioe);
			ioe.printStackTrace(Logger.err);
			C2OBridge.crash();
		} catch (RuntimeException re) {
			Logger.error("Error loading smartcopy.properties save data, corruption: " + re);
			re.printStackTrace(Logger.err);
			C2OBridge.crash();
		}
	}

	/**
	 * Shuts down the strategy. Saves currentlyOpen and blacklist data.
	 */
	@Override
	public final void shutdown() {
		Properties props = new Properties();
		String c = "";
		String b = "";
		for (String s : currentlyOpen) {
			if (c.length() > 0) {
				c += ",";
			}
			c += s;
		}
		for (String s : blacklist) {
			if (b.length() > 0) {
				b += ",";
			}
			b += s;
		}
		props.put(CURRENTLY_OPEN, c);
		props.put(BLACKLIST, b);

		// save to file
		try {
			FileOutputStream fos = new FileOutputStream(SMART_COPY_FILE);
			props.store(fos, null);
			fos.close();
		} catch (IOException ioe) {
			Logger.error("Unable to save SmartCopy strategy data: " + ioe);
			ioe.printStackTrace(Logger.err);
		}
	}

	/**
	 * Called by handleMessage with the info extracted from the email.
	 *
	 * @param action the action being undertaken: OPEN or CLOSE
	 * @param side the side being undertaken: BUY or SELL
	 * @param psize the position size opened by C2
	 * @param pair the currency pair being traded
	 * @param oprice the price at which C2 opened the position
	 */
	@Override
	public void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
		// initial info log
		Logger.info("[SmartCopyStrategy] Starting to handle trade: pair = " + pair + ", action = " + action);

		// get our account balance in account currency at the very start
		double accountBalance = getAccountBalance();

		double curPrice = getOandaPrice(side, pair);
		// double diff = roundPips(pair, Math.abs(curPrice - oprice));

		// get our position sizing
		int oandaPsize = convert(psize, accountBalance) * POS_SIZE_MULTIPLIER;

		if (action.equals(OPEN)) {
			// trying to open trade, determine if fresh trade, or it is on blacklist or local-currently-open list
			if (blacklist.contains(pair)) {
				// pair is currently blacklisted, show notification message and do nothing
				// even if trade has been re-entered as per re-entry rules, the currency pair will still stay on blacklist here
				Logger.info("[SmartCopyStrategy] C2 added to position for pair " + pair + ", which is blacklisted. No action taken.");
				return;
			}
			if (currentlyOpen.contains(pair)) {
				// determine if the pair is still open on Oanda, or if it has been stopped out
				List<JSONObject> trades = getTrades(pair);
				List<JSONObject> orders = getOrders(pair);
				if (trades.size() > 0) { // trade(s) placed and not yet stopped out
					// make sure the stop losses of all the trades are the same
					double tsl = -1;
					for (JSONObject t : trades) {
						if (tsl == -1) tsl = t.getDouble(STOP_LOSS);
						if (tsl != t.getDouble(STOP_LOSS))
							throw new RuntimeException("Not all stop losses of pair " + pair + " are the same");
					}
					double prevStopLoss = tsl;
					boolean slWithinLimits = false; // flag for whether the stop loss is small enough for our goal risk percentage
					double onePip = pipsToPrice(pair, 1);
					double accCurrencyPerPip = getAccCurrencyPerPip(pair);
					do {
						// decrement (if sell) or increment (if buy) tsl by 1 pip at a time
						if (side.equals(SELL)) tsl -= onePip;
						else tsl += onePip; // buy
						// calculate total risk of all the existing trades + new trade if all their stop losses are set to tsl
						double totalRiskPrice = 0;
						for (JSONObject t : trades) {
							int tUnits = t.getInt(UNITS);
							double accCurrencyPerPipForTrade = accCurrencyPerPip * tUnits;
							// figure out number of pips for this trade with the stop loss at 'tsl'
							double newStopLossPriceDiff = Math.abs(t.getDouble(PRICE) - tsl);
							double newStopLossPips = priceToPips(pair, newStopLossPriceDiff);
							double amount = accCurrencyPerPipForTrade * newStopLossPips;
							totalRiskPrice += amount;
						}
						double totalRiskPercentage = totalRiskPrice / accountBalance * 100;
						if (totalRiskPercentage <= ACC_STOPLOSS_PERCENT) {
							slWithinLimits = true;
						}
					} while (!slWithinLimits);

					// check that tsl is not lower than current price
					boolean clash = false;
					double tslCurPriceDiffPips = priceToPips(pair, Math.abs(curPrice - tsl));
					if ((side.equals(BUY) && tsl > curPrice) || (side.equals(SELL) && tsl < curPrice) || tslCurPriceDiffPips < ADD_TRADE_MIN_GAP) {
						clash = true;
					}

					// if no clash
					if (!clash) {
						// round new stoploss to 4 decimal places
						tsl = round4(tsl);

						// modify all existing trades
						for (JSONObject t : trades) {
							modifyTrade(t.getLong(ID), tsl, NO_TRAILING_STOP);
						}
						// create new trade
						long newTradeId = openTrade(side, oandaPsize, pair);
						// modify trade and give it the stop loss
						modifyTrade(newTradeId, tsl, NO_TRAILING_STOP);
						// debug message
						Logger.info("[SmartCopyStrategy] Added to existing position: stop-loss of all trades shifted from " +
								prevStopLoss + " to " + tsl);

						// try and work out why no round2 is needed for JPY pairs here
						if (pair.contains(JPY)) {
							Logger.info("[SmartCopyStrategy] Modified trade for JPY pair (" + pair + ") with stopLoss of " + tsl + ", no error");
						}
					} else {
						// clash, just print debug message and do nothing
						Logger.info("[SmartCopyStrategy] C2 added to existing position but we couldn't: stop-loss was at " +
								prevStopLoss + ", would needed to have been moved to " + tsl + " but couldn't.");
					}
				} else if (orders.size() > 0) { // order was placed and is still there, this should rarely ever happen...
					Logger.info("[SmartCopyStrategy] C2 added to position but our limit order hasn't even popped. Weird! pair = " + pair);
					// don't do anything else, this is a very rare and strange situation: wait for manual intervention
				} else { // the pair has definitely been stopped out: remove from currentlyOpen and add to blacklist
					Logger.info("[SmartCopyStrategy] C2 added to position for pair " + pair +
							", but our position was already stopped out. Adding [" + pair + "] to blacklist.");
					currentlyOpen.remove(pair);
					blacklist.add(pair);
				}
				// return early
				return;
			}

			// calculate appropriate stoploss for the trade
			double accCurrencyPerPip = getAccCurrencyPerPip(pair);
			double accCurrencyPerPipForTrade = accCurrencyPerPip * oandaPsize;
			int stopLossPips = (int) (accountBalance * (ACC_STOPLOSS_PERCENT / 100D) / accCurrencyPerPipForTrade);
			double stopLossPrice = pipsToPrice(pair, stopLossPips);

			// always place limit order
			double d = pipsToPrice(pair, LIMIT_ORDER_PIPS_DIFF);
			if (side.equals(BUY))
				d *= -1; // if buy, we want price to be LIMIT_ORDER_PIPS_DIFF pips *LOWER* for advantage
			double bound = oprice + d;
			double stopLoss = bound; // stopLoss is relative to bound, not curPrice, for limit orders
			if (side.equals(BUY)) stopLoss -= stopLossPrice;
			else stopLoss += stopLossPrice;
			// round bound and stoploss
			boolean isJpyPair = pair.contains(JPY);
			stopLoss = isJpyPair ? round2(stopLoss) : round4(stopLoss);
			bound = isJpyPair ? round2(bound) : round4(bound);
			// place the order
			long orderId = createOrder(side, oandaPsize, pair, bound);
			// modify the order to give it appropriate stop loss
			modifyOrder(orderId, stopLoss, NO_TRAILING_STOP);
			// add pair to currentlyOpen
			currentlyOpen.add(pair);
			// log info
			Logger.info("[SmartCopyStrategy] Created new order for pair [" + pair + "]. Order trigger price: " + oprice + ", units: " + oandaPsize);
		} else {
			// try to close all positions for the pair instantly
			// no checks required - even manually re-entered trades should be closed according to C2 strategy

			// get all trades for this pair
			List<JSONObject> list = getTrades(pair); // json of all currently open trades for this pair
			// get all orders for this pair
			ArrayList<JSONObject> olist = getOrders(pair);

			if (list.size() > 0 || olist.size() > 0) {
				String typeString = null;
				if (list.size() > 0) typeString = "open positions";
				if (olist.size() > 0) {
					if (typeString != null) {
						typeString += " and ";
						typeString += "outstanding orders";
					} else {
						typeString = "outstanding orders";
					}
				}
				Logger.info("[SmartCopyStrategy] Closing all " + typeString + " for pair [" + pair + "]");
			}

			for (JSONObject trade : list) {
				// close every trade returned for this pair
				long tradeId = trade.getLong(ID);
				closeTrade(tradeId);
			}
			for (JSONObject order : olist) {
				// close every order returned for this pair
				long orderId = order.getLong(ID);
				deleteOrder(orderId);
			}

			// remove the pair from the blacklist and currentlyOpen, if present
			if (blacklist.contains(pair)) {
				blacklist.remove(pair);
				if (currentlyOpen.contains(pair)) {
					throw new RuntimeException(pair + " was in both blacklist and currentlyOpen - shouldn't happen");
				}
			} else if (currentlyOpen.contains(pair)) {
				currentlyOpen.remove(pair);
			}
		}
	}
}
