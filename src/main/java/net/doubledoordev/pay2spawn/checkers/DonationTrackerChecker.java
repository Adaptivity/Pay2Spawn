/*
 * Copyright (c) 2014, DoubleDoorDevelopment
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of DoubleDoorDevelopment nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.doubledoordev.pay2spawn.checkers;

import net.doubledoordev.pay2spawn.hud.DonationsBasedHudEntry;
import net.doubledoordev.pay2spawn.hud.Hud;
import net.doubledoordev.pay2spawn.util.Donation;
import net.doubledoordev.pay2spawn.util.Helper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.common.config.Configuration;

import java.io.IOException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.doubledoordev.pay2spawn.util.Constants.BASECAT_TRACKERS;
import static net.doubledoordev.pay2spawn.util.Constants.JSON_PARSER;

/**
 * For donation-tracker.com
 *
 * @author Dries007
 */
public class DonationTrackerChecker extends AbstractChecker implements Runnable
{
    public static final DonationTrackerChecker INSTANCE     = new DonationTrackerChecker();
    public final static String                 NAME         = "donation-tracker";
    public final static String                 CAT          = BASECAT_TRACKERS + '.' + NAME;
    public final static String                 URL          = "https://www.donation-tracker.com/customapi/?";
    public final static Pattern                HTML_REGEX   = Pattern.compile("<td.*?>(.+?)<\\/td.*?>");
    public final static Pattern                AMOUNT_REGEX = Pattern.compile(".?(\\d+(?:\\.|,)\\d\\d)\\w?.?");

    //public final static Pattern                HTML_REGEX    = Pattern.compile("From: (.+?) - .(\\d+\\.\\d\\d) - (.+?) \\| ");

    DonationsBasedHudEntry topDonationsBasedHudEntry, recentDonationsBasedHudEntry;

    String Channel = "", APIKey = "";
    boolean          enabled  = true;
    int              interval = 3;
    SimpleDateFormat sdf      = new SimpleDateFormat("dd.MM.yyyy - HH:mm:ss");

    private DonationTrackerChecker()
    {
        super();
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void init()
    {
        Hud.INSTANCE.set.add(topDonationsBasedHudEntry);
        Hud.INSTANCE.set.add(recentDonationsBasedHudEntry);

        new Thread(this, getName()).start();
    }

    @Override
    public boolean enabled()
    {
        return enabled && !Channel.isEmpty() && !APIKey.isEmpty();
    }

    @Override
    public void doConfig(Configuration configuration)
    {
        configuration.addCustomCategoryComment(CAT, "This is the checker for donation-tracker.com");

        enabled = configuration.get(CAT, "enabled", enabled).getBoolean(enabled);
        Channel = configuration.get(CAT, "Channel", Channel).getString();
        APIKey = configuration.get(CAT, "APIKey", APIKey).getString();
        interval = configuration.get(CAT, "interval", interval, "The time in between polls (in seconds).").getInt();

        recentDonationsBasedHudEntry = new DonationsBasedHudEntry(configuration, CAT + ".recentDonations", -1, 2, 5, "$name: $$amount", "-- Recent donations --", CheckerHandler.RECENT_DONATION_COMPARATOR);
        topDonationsBasedHudEntry = new DonationsBasedHudEntry(configuration, CAT + ".topDonations", -1, 1, 5, "$name: $$amount", "-- Top donations --", CheckerHandler.AMOUNT_DONATION_COMPARATOR);
    }

    @Override
    public DonationsBasedHudEntry[] getDonationsBasedHudEntries()
    {
        return new DonationsBasedHudEntry[] {topDonationsBasedHudEntry, recentDonationsBasedHudEntry};
    }

    @Override
    public void run()
    {
        try
        {
            JsonObject root = JSON_PARSER.parse(Helper.readUrl(new URL(URL + "channel=" + Channel + "&api_key=" + APIKey + "&custom=1"))).getAsJsonObject();
            if (root.getAsJsonPrimitive("api_check").getAsInt() == 1)
            {
                JsonArray donations = root.getAsJsonArray("donation_list");
                for (JsonElement jsonElement : donations)
                {
                    Donation donation = getDonation(jsonElement.getAsString());
                    topDonationsBasedHudEntry.add(donation);
                    doneIDs.add(donation.id);
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        while (true)
        {
            doWait(interval);
            try
            {
                JsonObject root = JSON_PARSER.parse(Helper.readUrl(new URL(URL + "channel=" + Channel + "&api_key=" + APIKey + "&custom=1"))).getAsJsonObject();
                if (root.getAsJsonPrimitive("api_check").getAsInt() == 1)
                {
                    JsonArray donations = root.getAsJsonArray("donation_list");
                    for (JsonElement jsonElement : donations)
                    {
                        process(getDonation(jsonElement.getAsString()), true);
                    }
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private Donation getDonation(String html)
    {
        ArrayList<String> htmlMatches = new ArrayList<>();
        Matcher htmlMatcher = HTML_REGEX.matcher(html);
        while (htmlMatcher.find()) htmlMatches.add(htmlMatcher.group(1));
        String[] data = htmlMatches.toArray(new String[htmlMatches.size()]);

        Matcher amountMatcher = AMOUNT_REGEX.matcher(data[3]);
        amountMatcher.find();
        double amount = Double.parseDouble(amountMatcher.group(1).replace(',', '.'));

        long time = new Date().getTime();
        try
        {
            time = sdf.parse(data[2]).getTime();
        }
        catch (ParseException e)
        {
            e.printStackTrace();
        }
        return new Donation(data[2], amount, time, data[0], data[1]);
    }
}
