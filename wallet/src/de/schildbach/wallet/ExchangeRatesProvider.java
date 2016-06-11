/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Currency;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateUtils;
import cc.mazacoin.wallet.R;
import de.schildbach.wallet.util.GenericUtils;
import de.schildbach.wallet.util.Io;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider
{
	public static class ExchangeRate
	{
		public ExchangeRate(@Nonnull final String currencyCode, @Nonnull final BigInteger rate, @Nonnull final String source)
		{
			this.currencyCode = currencyCode;
			this.rate = rate;
			this.source = source;
		}

		public final String currencyCode;
		public final BigInteger rate;
		public final String source;

		@Override
		public String toString()
		{
			return getClass().getSimpleName() + '[' + currencyCode + ':' + GenericUtils.formatValue(rate, Constants.BTC_MAX_PRECISION, 0) + ']';
		}
	}

	public static final String KEY_CURRENCY_CODE = "currency_code";
	private static final String KEY_RATE = "rate";
	private static final String KEY_SOURCE = "source";

	@CheckForNull
	private Map<String, ExchangeRate> exchangeRates = null;
    @CheckForNull
    private BigDecimal zetacoinRate = null;
    private String zetacoinRateMethodSourceName = "";
    private String[] zetacoinRateMethods = null;
	private long lastUpdated = 0;
    private long lastUpdatedZET = 0;
    private SharedPreferences prefs;

	private static final URL BITCOINAVERAGE_URL;
	private static final String[] BITCOINAVERAGE_FIELDS = new String[] { "24h_avg" };
	private static final URL BITCOINCHARTS_URL;
	private static final String[] BITCOINCHARTS_FIELDS = new String[] { "24h", "7d", "30d" };
	private static final URL BLOCKCHAININFO_URL;
	private static final String[] BLOCKCHAININFO_FIELDS = new String[] { "15m" };

	// https://bitmarket.eu/api/ticker

	static
	{
		try
		{
			BITCOINAVERAGE_URL = new URL("https://api.bitcoinaverage.com/ticker/all");
			BITCOINCHARTS_URL = new URL("http://api.bitcoincharts.com/v1/weighted_prices.json");
			BLOCKCHAININFO_URL = new URL("https://blockchain.info/ticker");
		}
		catch (final MalformedURLException x)
		{
			throw new RuntimeException(x); // cannot happen
		}
	}

	private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;

	private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

	@Override
	public boolean onCreate()
	{
        this.prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        return true;
	}

	public static Uri contentUri(@Nonnull final String packageName)
	{
		return Uri.parse("content://" + packageName + '.' + "exchange_rates");
	}

	@Override
	public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
	{
		final long now = System.currentTimeMillis();

		if (exchangeRates == null || now - lastUpdated > UPDATE_FREQ_MS)
		{
			Map<String, ExchangeRate> newExchangeRates = null;

                        BigDecimal newZetacoinRate = BigDecimal.valueOf(coinGetBTCValue());//getZetacoinRate();

                        if (newExchangeRates == null)
				newExchangeRates = requestExchangeRates(newZetacoinRate, zetacoinRateMethodSourceName,
                        BITCOINAVERAGE_URL, BITCOINAVERAGE_FIELDS);
			if (newExchangeRates == null)
				newExchangeRates = requestExchangeRates(newZetacoinRate, zetacoinRateMethodSourceName,
                        BITCOINCHARTS_URL, BITCOINCHARTS_FIELDS);
			if (newExchangeRates == null)
				newExchangeRates = requestExchangeRates(newZetacoinRate, zetacoinRateMethodSourceName,
                        BLOCKCHAININFO_URL, BLOCKCHAININFO_FIELDS);

			if (newExchangeRates != null)
			{
				exchangeRates = newExchangeRates;
				lastUpdated = now;
			}
		}

		if (exchangeRates == null)
			return null;

		final MatrixCursor cursor = new MatrixCursor(new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE, KEY_SOURCE });

		if (selection == null)
		{
			for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet())
			{
				final ExchangeRate rate = entry.getValue();
				cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
			}
		}
		else if (selection.equals(KEY_CURRENCY_CODE))
		{
			final String selectedCode = selectionArgs[0];
			ExchangeRate rate = selectedCode != null ? exchangeRates.get(selectedCode) : null;

			if (rate == null)
			{
				final String defaultCode = defaultCurrencyCode();
				rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

				if (rate == null)
				{
					rate = exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);

					if (rate == null)
						return null;
				}
			}

			cursor.newRow().add(rate.currencyCode.hashCode()).add(rate.currencyCode).add(rate.rate.longValue()).add(rate.source);
		}

		return cursor;
	}

	private String defaultCurrencyCode()
	{
		try
		{
			return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
		}
		catch (final IllegalArgumentException x)
		{
			return null;
		}
	}

	public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor)
	{
		final String currencyCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
		final BigInteger rate = BigInteger.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE)));
		final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

		return new ExchangeRate(currencyCode, rate, source);
	}

	@Override
	public Uri insert(final Uri uri, final ContentValues values)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(final Uri uri, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String getType(final Uri uri)
	{
		throw new UnsupportedOperationException();
	}

	private static Map<String, ExchangeRate> requestExchangeRates(BigDecimal zet, String zetRateSource, final URL url, final String... fields)
	{
		final long start = System.currentTimeMillis();

		HttpURLConnection connection = null;
		Reader reader = null;
                
		try
		{
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
				final StringBuilder content = new StringBuilder();
				Io.copy(reader, content);

				final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

				rates.put("#BTC", new ExchangeRate("#BTC", zet.movePointRight(8).toBigIntegerExact(), zetRateSource));
				
				final JSONObject head = new JSONObject(content.toString());
				for (final Iterator<String> i = head.keys(); i.hasNext();)
				{
					final String currencyCode = i.next();
					if (!"timestamp".equals(currencyCode))
					{
						final JSONObject o = head.getJSONObject(currencyCode);
						
						for (final String field : fields)
						{
							final String rateStr = o.optString(field, null);

							if (rateStr != null)
							{
								try
								{	
									BigDecimal rate = new BigDecimal(rateStr);
									BigDecimal result = rate.multiply(zet);
									
									if (result.signum() > 0)
									{
										rates.put(currencyCode, new ExchangeRate(currencyCode, result.movePointRight(8).toBigInteger(), url.getHost()));
										break;
									}
								}
								catch (final ArithmeticException x)
								{
									log.warn("problem fetching exchange rate: " + currencyCode, x);
								}
								catch (Exception x)
								{
									log.warn("problem fetching ZET", x);
								}
							}
						}
					}
				}

				log.info("fetched exchange rates from " + url + ", took " + (System.currentTimeMillis() - start) + " ms");

				return rates;
			}
			else
			{
				log.warn("http status " + responseCode + " when fetching " + url);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching exchange rates", x);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}

			if (connection != null)
				connection.disconnect();
		}

		return null;
	}

       private static Double coinGetBTCValue() {

        //final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        Double btcRate = 0.0;
        String url = "https://bittrex.com/api/v1.1/public/getticker?market=BTC-MZC";

        try {
            // final String currencyCode = currencies[i];
            final URL URLC = new URL(url);
            final HttpURLConnection conn = (HttpURLConnection)URLC.openConnection();
            conn.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            conn.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
            conn.connect();

            final StringBuilder content = new StringBuilder();

            Reader reader = null;
            try
            {
                reader = new InputStreamReader(new BufferedInputStream(conn.getInputStream(), 1024));
                Io.copy(reader, content);
                final JSONObject obj = new JSONObject(content.toString());
                JSONObject result = obj.getJSONObject("result");
                btcRate = result.getDouble("Bid");
            }
            finally
            {
                if (reader != null)
                    reader.close();
            }
            return btcRate;
        }
        catch (final IOException x)
        {
            x.printStackTrace();
        }
        catch (final JSONException x)
        {
            x.printStackTrace();
        }

        return null;
    }

    private BigDecimal getZetacoinRate() {
        final long now = System.currentTimeMillis();

        if (zetacoinRate == null || now - lastUpdatedZET > UPDATE_FREQ_MS) {
            // obtain preferred method from preferences
            final String rateMethodNameIndexStr = prefs.getString(Constants.PREFS_KEY_EXCANGE_RATE_METHOD, Constants.PREFS_DEFAULT_EXCHANGE_RATE_METHOD);
            ZETBTCRateMethod method = ZETBTCRateMethod.fromNameIndex(Integer.parseInt(rateMethodNameIndexStr));

            // set method name
            zetacoinRateMethodSourceName = method.getName(getZetacoinRateMethods());

            // check zetacoin rate and get AVG upon failure
            BigDecimal newZetacoinRate = requestZetacoinRates(getContext().getResources(), method);

            // upon failure from single source method, use average
            if (newZetacoinRate == null)
                newZetacoinRate = requestZetacoinRates(getContext().getResources(), ZETBTCRateMethod.AVG);

            if (newZetacoinRate != null) {
                zetacoinRate = newZetacoinRate;
                lastUpdatedZET = now;
            }
        }

        return zetacoinRate;

    }

    private String[] getZetacoinRateMethods() {
        if (zetacoinRateMethods == null)
            zetacoinRateMethods = getContext().getResources().getStringArray(R.array.preferences_exchange_rate_method_labels);
        return zetacoinRateMethods;
    }
	
	private static BigDecimal requestZetacoinRates(Resources res, final ZETBTCRateMethod method)
	{
		final long start = System.currentTimeMillis();

        // if rate method is an aggregation (not a source)
        if (!method.rateSource) {
            Set<ZETBTCRateMethod> rateSources = ZETBTCRateMethod.getRateSources();
            Map<ZETBTCRateMethod, BigDecimal> rateCache =
                    new EnumMap<ZETBTCRateMethod, BigDecimal>(ZETBTCRateMethod.class);

            // obtain rates from every source and put htem in the cache
            for (ZETBTCRateMethod rateSource : rateSources) {
                BigDecimal zetaRate = requestZetacoinRates(res, rateSource);
                if (zetaRate != null)
                    rateCache.put(rateSource, zetaRate);
            }

            // use the cache for aggregation of rates (return null if cache is empty)
            if (rateCache.size() <= 0) return null;
            return method.getAggregatedValue(rateCache.values().toArray(new BigDecimal[rateCache.size()]));
        }

        final URL url = method.getUrl(res);
        final String[] fields = method.getFields(res);
		HttpURLConnection connection = null;
		Reader reader = null;

		try
		{
			connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Constants.UTF_8);
				final StringBuilder content = new StringBuilder();
				Io.copy(reader, content);

				BigDecimal rate = null;

				final JSONObject o = method.getRateStringJSONObject(content.toString());
                if (o == null)
                    return null;
				
				for (final String field : fields)
				{
					final String rateStr = o.optString(field, null);

					if (rateStr != null)
					{
						try
						{
							rate = new BigDecimal(rateStr);
						}
						catch (final ArithmeticException x)
						{
							log.warn("problem fetching exchange rate: ZET", x);
						}
					}
				}
				log.info("fetched exchange rates from " + url + ", took " + (System.currentTimeMillis() - start) + " ms");

				return rate;
			}
			else
			{
				log.warn("http status " + responseCode + " when fetching " + url);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching exchange rates", x);
		}
		finally
		{
			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}

			if (connection != null)
				connection.disconnect();
		}

		return null;
	}

    public enum ZETBTCRateMethod {
        AVG(0), MIN(1), MAX(2),

        BTER(3, R.string.exchange_rate_url_bter,
                R.array.exchange_rate_fields_bter),
        MINTPAL(4, R.string.exchange_rate_url_mintpal,
                R.array.exchange_rate_fields_mintpal),
        CRYPTSY(5, R.string.exchange_rate_url_cryptsy,
                R.array.exchange_rate_fields_cryptsy);

        private int nameIndex;
        private int urlResourceId;
        private int fieldsArrayResId;
        private boolean rateSource = false;

        ZETBTCRateMethod(int nameIndex) { this.nameIndex = nameIndex; }

        ZETBTCRateMethod(int nameIndex, int urlResourceId, int fieldsArrayResId) {
            this(nameIndex);
            this.urlResourceId = urlResourceId;
            this.fieldsArrayResId = fieldsArrayResId;
            this.rateSource = true;
        }

        private static Set<ZETBTCRateMethod> rateSources = EnumSet.noneOf(ZETBTCRateMethod.class);
        private static ZETBTCRateMethod[] methodIndex = new ZETBTCRateMethod[ZETBTCRateMethod.values().length];

        static {
            for (ZETBTCRateMethod method : ZETBTCRateMethod.values()) {
                if (method.rateSource)
                    rateSources.add(method);
                methodIndex[method.nameIndex] = method;
            }
        }

        public String getName(String[] methods) {
            return methods[nameIndex];
        }

        public URL getUrl(Resources resources) {
            final String urlString = resources.getString(urlResourceId);
            URL url = null;
            try {
                url = new URL(urlString);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
            return url;
        }

        public String[] getFields(Resources resources) {
            return resources.getStringArray(fieldsArrayResId);
        }

        public JSONObject getRateStringJSONObject(String json) throws JSONException {
            switch (this) {
                case BTER:
                    return new JSONObject(json);
                case MINTPAL:
                    JSONArray arr = new JSONArray(json);
                    return arr.length() > 0 ? arr.getJSONObject(0) : null;
                case CRYPTSY:
                    JSONObject obj = new JSONObject(json);
                    JSONObject markets = obj.getJSONObject("markets");
                    return markets.getJSONObject("MZC");
                default:
                    return null;
            }
        }

        public BigDecimal getAggregatedValue(BigDecimal[] values) {
            if (values == null)
                return null;
            BigDecimal sum = null;
            BigDecimal min = null;
            BigDecimal max = null;
            for (BigDecimal value: values) {
                switch (this) {
                    case AVG:
                        if (sum == null) sum = new BigDecimal(0);
                        sum = sum.add(value);
                        break;
                    case MIN:
                        if (min == null || value.compareTo(min) < 0) min = value;
                        break;
                    case MAX:
                        if (max == null || value.compareTo(max) > 0) max = value;
                        break;
                }
            }
            switch (this) {
                case AVG:
                    return sum != null ? sum.divide(new BigDecimal(values.length)) : null;
                case MIN:
                    return min;
                case MAX:
                    return max;
                default:
                    return null;
            }
        }

        public static Set<ZETBTCRateMethod> getRateSources() {
            return rateSources;
        }
        public static ZETBTCRateMethod fromNameIndex(int nameIndex) { return methodIndex[nameIndex]; }
    }
}
