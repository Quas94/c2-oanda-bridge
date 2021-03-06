package com.quas.c2obridge.strategy;

import com.quas.c2obridge.Logger;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Strategy #1:
 * - exact clone of C2 strategy with position sizing multiplied by constant
 *
 * Created by Quasar on 2/12/2015.
 */
public class CopyStrategyHandler extends StrategyHandler {

	/** Multiplier applied against C2 position sizing */
	private static final int POS_SIZE_MULTIPLIER = 3;

	/**
	 * Constructor for the copy strategy.
	 *
	 * @param accountId the account id for the reverse strategy
	 */
	public CopyStrategyHandler(int accountId) {
		super(accountId);
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
	public void handleInfo(String action, String side, int psize, String pair, double oprice) throws IOException {
		double compare = getOandaPrice(side, pair);
		double diff = Math.abs(compare - oprice);

		if (action.equals(OPEN)) {
			// diff = difference between C2's opening price and oanda's current price, in pips
			if (diff <= MAX_PIP_DIFF || (side.equals(BUY) && compare < oprice) || (side.equals(SELL) && compare > oprice)) {
				// pip difference is at most negative 5 pips (in direction of our favour)
				// try to place an order

				double accountBalance = getAccountBalance();
				// get our position sizing
				int oandaPsize = convert(psize, accountBalance) * POS_SIZE_MULTIPLIER;
				// actually place the trade
				openTrade(side, oandaPsize, pair);
			} else {
				// missed opportunity
				Logger.error("[CopyStrategy] missed opportunity to place order to " + side + " " + pair + " (pip diff = " + diff +
						" - actiontype = " + side + ", compare = " + compare + ", oprice = " + oprice + ")");
				// @TODO maybe place limit order?
			}
		} else {
			// close position instantly
			List<JSONObject> list = getTrades(pair); // json of all currently open trades for this pair
			for (JSONObject trade : list) {
				// close every trade returned for this pair
				long tradeId = trade.getLong(ID);
				closeTrade(tradeId);
			}
		}
	}
}
