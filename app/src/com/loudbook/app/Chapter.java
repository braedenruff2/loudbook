package com.loudbook.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A chapter as the page script (assets/web/loudbook.js) hands it over. */
final class Chapter {
    // the letter A said as a letter: the phone voice reads a lone "A" before a word as "uh" ("Plan A failed",
    // "an A on the test"); "Eigh" it says as the letter. Only for the phone voice (Gemini reads A fine).
    private static final java.util.regex.Pattern LETTER_A = java.util.regex.Pattern.compile(
        "(?<=\\b(?:(?i:Plan|Type|Grade|Rank|Tier|Class|Option|Section|Vitamin|Group|Team|Block|Wing|Floor|Exhibit|Appendix|Part|Side|Building|Gate|Platform|Row|Seat|Zone|Sector|Squad|Unit|Category|Size|Batch|Phase|Model|Cell|Hall|Ward|Lot|Route|Track|Pod|Bay|Deck|Hangar|Terminal|Dorm|Lab|Tower)|[Aa]n) )A(?![\\p{L}\\d'\u2019+-]|\\.\\p{L})");
    // Verbs the phone voice says as the noun (its guess at a sentence start, or always): "Close the door" /
    // "and close your eyes" ("cloce"), "Excuse me" ("excuce"), "Use your head", "Abuse the system", "Separate them" ("sep-rit"),
    // "Record the meeting", "Present the gift", "Escort the prisoner", "Tear it down" (and more at a sentence start), "wind the clock" ("wind" as the weather), "he wound the rope" ("woond"),
    // "took a bow" / "bow your head" ("boh", as the weapon), "Polish the silver" ("pole-ish"). Respelled the way it says them right
    // (checked in the Kokoro lab, 2026-11-01). Only for the phone voice: Gemini reads these fine.
    private static final String OBJ = "(?=[ \\t]+(?:the|a|an|your|my|his|her|their|our|its|it|them|him|me|us|this|that|these|those|every|each|all|both|everything|everyone|everybody|someone|somebody|anyone|anybody|yourself|myself|himself|herself|themselves|ourselves)\\b)";
    private static final java.util.regex.Pattern CLOSE = java.util.regex.Pattern.compile(
        "(?<!\\b(?:[Aa]|[Tt]he|[Mm]ore|[Mm]ost|[Ss]uch|[Ss]o|[Tt]oo|[Aa]s|[Hh]ow|[Vv]ery|[Qq]uite|[Pp]retty|[Rr]eally|[Tt]his|[Tt]hat|[Gg]ot|[Cc]ame|[Cc]ome|[Gg]et|[Ss]tood|[Ww]as|[Ww]ere|[Ii]s|[Aa]re|[Bb]e|[Bb]een|[Ff]elt|[Ss]eemed|[Dd]rew|[Ll]eaned|[Ss]tayed|[Ss]tay|[Kk]ept|[Hh]eld|[Ss]at|[Ll]ay|[Mm]oved|[Pp]ulled|[Uu]p|[Dd]rawing|[Gg]etting|[Cc]oming|[Ss]tanding|[Ss]itting|[Ss]taying|[Ff]ollowed|[Ss]wam|[Ff]lew|[Cc]reeping|[Cc]rept)[ \\t])\\b([Cc])lose" + OBJ.replace("|a|an|", "|"));
    // close with no object it says "cloce" too: "the doors close behind him", "her eyes close slowly", "the walls close in on",
    // "let them close." (only after "the/her... <plural>" and before a word that makes it the verb; lab 2026-11-01)
    private static final java.util.regex.Pattern CLOSE_INTR = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Tt]he|[Hh]er|[Hh]is|[Tt]heir|[Ii]ts|[Mm]y|[Oo]ur|[Yy]our|[Tt]hese|[Tt]hose)[ \\t]\\p{Ll}{2,20}s[ \\t])close(?=[ \\t]+(?:slowly|softly|quietly|automatically|shut)\\b|[ \\t]+in(?:[ \\t]*[.!]|[ \\t]+on\\b))"
        + "|(?<=\\b[Ll]et[ \\t](?:me|him|her|them|us|it)[ \\t])close(?=[ \\t]*[.!]|[ \\t]+(?:in|up)\\b)"
        + "|\\b(?<![Aa][ \\t]|[Ss]o[ \\t]|[Vv]ery[ \\t]|[Ww]as[ \\t]|[Ii]s[ \\t])(?<!\\b(?:[Gg]ot|[Gg]et|[Cc]ame|[Ss]tood|[Ss]tay|[Ss]tayed|[Kk]ept|[Ff]ollowed|[Ww]ere|[Aa]re|[Hh]eld|[Ll]ay|[Ss]at|[Pp]ulled|[Mm]oved|[Dd]rew|[Ff]elt)[ \\t])([Cc])lose(?=[ \\t]+in[ \\t]+on\\b)");
    private static final java.util.regex.Pattern EXCUSE = java.util.regex.Pattern.compile(
        "(?<!\\b(?:[Aa]n|[Tt]he|[Nn]o|[Aa]ny|[Pp]oor|[Gg]ood|[Bb]ad|[Ll]ame|[Pp]erfect|[Ee]very|[Ss]ome|[Aa]nother|[Bb]est|[Oo]nly|[Ff]eeble|[Ff]limsy|[Cc]onvenient|[Mm]y|[Hh]is|[Hh]er|[Yy]our|[Tt]heir|[Oo]ur|[Ii]ts)[ \\t])\\b([Ee])xcuse(?=[ \\t]+(?:me|us|him|her|them|my|our|your|his|their|myself|himself|herself|themselves|yourself|yourselves|ourselves|this|that|the)\\b)");
    // (at a sentence start only: mid-sentence it says these right)
    private static final java.util.regex.Pattern START_VERB = java.util.regex.Pattern.compile(
        "(?:^|(?<=[.!?]|[.!?][\"\u201d\u2019')]|[\"\u201c\u2018(]))([ \\t]*)(Use|Abuse|Separate|Record|Present|Polish|Tear|Permit|Insult|Increase|Decrease|Estimate|Moderate|Conduct|Project|Object|Protest|Suspect|Convict|Console|Contract|Torment|Escort|Transport|Export|Insert|Desert|Contest)" + OBJ);
    private static final java.util.Map<String, String> START_SAID = new java.util.HashMap<>();
    static {
        String[] w = {"Use", "Uze", "Abuse", "Abuze", "Separate", "Sepper-ate", "Record", "Re-cord", "Present", "Pree-zent", "Polish", "Pollish",
            "Tear", "Tare", "Permit", "Per-mit", "Insult", "In-sult", "Increase", "In-crease", "Decrease", "Dee-crease",
            "Estimate", "Esti-mate", "Moderate", "Moder-ate", "Conduct", "Kun-duct", "Project", "Pruh-ject", "Object", "Ub-ject",
            "Protest", "Pruh-test", "Suspect", "Suh-spect", "Convict", "Kun-vict", "Console", "Kun-sole", "Contract", "Kun-tract",
            "Torment", "Tor-ment", "Escort", "Ess-cort", "Transport", "Trans-port", "Export", "Ex-port",
            "Insert", "In-sert", "Desert", "Dessert", "Contest", "Kun-test"};
        for (int i = 0; i < w.length; i += 2) START_SAID.put(w[i], w[i + 1]);
    }
    // after an adverb ("they always use it", "she carefully recorded"... "he quickly present(s)") it takes them for nouns too
    // (lab 2026-11-01: always/often/usually/also/really and any -ly word; "never", "will", "to" are fine). Not after "the only",
    // "a lovely": a determiner before the adverb makes the word a noun.
    private static final java.util.regex.Pattern ADV_VERB = java.util.regex.Pattern.compile(
        "(?<!\\b(?:[Tt]he|[Aa]|[Aa]n|[Hh]is|[Hh]er|[Ii]ts|[Tt]heir|[Mm]y|[Yy]our|[Oo]ur|[Tt]his|[Tt]hat|[Nn]o|[Aa]ny|[Ee]very|[Ss]ome|[Ee]ach|[Ww]as|[Ww]ere|[Ii]s|[Aa]re|[Bb]e|[Bb]een|[Bb]eing|[Aa]m)[ \\t])"
        + "\\b((?!(?:early|only|family|ally|belly|bully|jelly|lily|rally|tally|fly|reply|supply|apply|rely|assembly|holy|ugly|lovely|lonely|friendly|elderly|silly|jolly|daily|weekly|monthly|yearly|hourly|nightly|likely|deadly|costly|bodily|orderly|lowly|kindly|curly|burly|surly|chilly|hilly|oily|wily|lively|comely|homely|manly|womanly|worldly|scholarly|cowardly|stately|ghastly|ghostly|beastly|sickly|smelly|bubbly|wobbly|prickly|melancholy|anomaly|monopoly|italy|july|sally|molly|holly|emily|lily|kelly|billy|willy|polly)\\b)"
        + "(?:[a-z]+ly|always|often|also|sometimes|usually|seldom|then|now|soon|just|still|even)[ \\t]+)"
        + "(use|abuse|separate|record|present|polish|tear|permit|insult|increase|decrease|estimate|moderate|conduct|project|object|protest|suspect|convict|console|contract|torment|escort|transport|export|insert|desert|contest)" + OBJ);
    // and after "had better", "would rather", "you'd", "let's", "let me", "help him", "make them", "did he", "why not", "go", "come and", "Now," "Please,"
    // (lab 2026-11-01; "I'll", "don't", "to", "will", "then", "just" are fine)
    private static final java.util.regex.Pattern PRE_VERB = java.util.regex.Pattern.compile(
        "(?<=\\b(?:(?:[Yy]ou|[Tt]hey|I|[Hh]e|[Ss]he|[Ww]e)['\u2019]d|[Ll]et['\u2019]s|(?:[Ll]et|[Hh]elp|[Hh]elped|[Mm]ake|[Mm]ade|[Ww]atch|[Ss]ee)[ \\t](?:me|him|her|us|them|you)"
        + "|(?:[Dd]id|[Dd]oes|[Dd]o)[ \\t](?:he|she|they|you|I|we|it)|[Ww]hy[ \\t]not|(?:[Hh]ad|[Ww]ould|['\u2019]d)[ \\t](?:better|rather)|[Gg]o|[Cc]ome[ \\t]and|(?:[Nn]ow|[Pp]lease|[Qq]uick|[Hh]urry|[Oo]kay|OK|[Nn]ext|[Ff]irst|[Tt]hen),)[ \\t])"
        + "(use|abuse|separate|record|present|polish|tear|permit|insult|increase|decrease|estimate|moderate|conduct|project|object|protest|suspect|convict|console|contract|torment|escort|transport|export|insert|desert|contest)" + OBJ);
    // and after a plural subject ("the guards escort the prisoner", "mages use their mana", "many suspect him") or "and"
    // ("stood up and record the scene"): present-tense web fiction (lab 2026-11-01)
    private static final java.util.regex.Pattern PL_VERB = java.util.regex.Pattern.compile(
        "(?<=\\b(?:(?:[Tt]he|[Tt]hese|[Tt]hose|[Hh]is|[Hh]er|[Tt]heir|[Oo]ur|[Mm]y|[Yy]our|[Mm]any|[Ss]ome|[Aa]ll|[Bb]oth|[Mm]ost|[Ff]ew|[Tt]wo|[Tt]hree)[ \\t]\\p{Ll}{1,19}[a-rt-z]s"
        + "|[A-Z]\\p{Ll}{1,19}[a-rt-z]s|[Mm]any|[Ff]ew|[Bb]oth|[Oo]thers|[Pp]eople|and)[ \\t])"
        + "(use|abuse|separate|record|present|polish|tear|permit|insult|increase|decrease|estimate|moderate|conduct|project|object|protest|suspect|convict|console|contract|torment|escort|transport|export|insert|desert|contest)" + OBJ);
    private static final java.util.regex.Pattern AND_USE = java.util.regex.Pattern.compile(
        "(?<=\\b(?:and|then|[Pp]lease|[Jj]ust|[Nn]ow|[Ss]o|[Oo]r)[ \\t])use" + OBJ);
    private static final java.util.regex.Pattern WIND = java.util.regex.Pattern.compile(
        "(?<=\\b(?:to|will|would|could|should|can|must|might|[Pp]lease|did|didn't|don't|doesn't|won't|can't|I|you|we|they)[ \\t])wind(?=[ \\t]+(?:the|it|them|his|her|my|your|their|its|up|down|around|round|through|back)\\b)");
    private static final java.util.regex.Pattern WOUND = java.util.regex.Pattern.compile(
        "(?<=\\b(?:I|[Hh]e|[Ss]he|[Ww]e|[Tt]hey|[Yy]ou|[Ii]t|had|have|has|having|was|were|been|tightly|loosely|carefully|slowly|then|[Tt]hen|path|road|river|trail|stream|line|queue|procession)[ \\t])wound(?=[ \\t]+(?:the|a|an|it|them|his|her|my|your|their|its|our|up|around|round|through|down|back|tight|tightly|together|itself|himself|herself|themselves|along|across|between|past|over)\\b)");
    private static final java.util.regex.Pattern BOW = java.util.regex.Pattern.compile(
        "\\b(?:((?:[Tt]ook|[Tt]ake|[Tt]akes|[Tt]aking|[Gg]ave|[Gg]ive|[Gg]ives|[Gg]iving|[Ss]wept)[ \\t]+(?:a|an|another)[ \\t]+)|((?:deep|slight|low|polite|respectful|curt|quick|formal|shallow|courtly|mocking|theatrical|elaborate|sweeping|graceful|perfunctory|brief|final|exaggerated|stiff)[ \\t]+))bow(s?)\\b"
        + "|(?<=\\b(?:I|[Yy]ou|[Ww]e|[Tt]hey|to|will|would|must|should|shall|not|and|then|all|[Ee]veryone|[Pp]lease|[Hh]e|[Ss]he|[Ii]t)[ \\t])bow(s?)(?=[ \\t]+(?:to|before|down|low|deeply|slightly|respectfully|politely|(?:his|her|their|your|my|our|its)[ \\t]+heads?)\\b)"
        + "|(?:^|(?<=[.!?\"\u201c] ))Bow(?=[ \\t]+(?:to|before|down|low|deeply|(?:his|her|their|your|my|our|its)[ \\t]+heads?)\\b)");
    // "She read the note" / "The sign read" is past ("red"; the voice said "reed": "he/she/it read" can't be the present, that's "reads").
    // "did he read", "let her read" stay. "She dove into the lake" ("duv", the bird) -> dohv. Lead the metal ("a lead pipe",
    // "made of lead", "heavy as lead"; the voice said "leed") -> led. Checked in the Kokoro lab, 2026-11-01.
    private static final java.util.regex.Pattern READ = java.util.regex.Pattern.compile(
        "(?<!\\b(?:[Dd]id|[Dd]oes|[Dd]o|[Cc]an|[Cc]ould|[Ww]ill|[Ww]ould|[Ss]hould|[Ss]hall|[Mm]ay|[Mm]ight|[Mm]ust|[Dd]idn't|[Dd]oesn't|[Ww]on't|[Cc]an't|[Cc]ouldn't|[Ww]ouldn't|[Ss]houldn't|[Ll]et|[Hh]elp|[Mm]ake|[Mm]ade|[Ww]atch|[Ww]atched|[Hh]ear|[Hh]eard|[Ss]ee|[Ss]aw)[ \\t])"
        + "\\b((?:[Hh]e|[Ss]he|[Ii]t|(?:[Tt]he|[Aa]|[Aa]n|[Ii]ts|[Hh]is|[Hh]er|[Tt]heir)[ \\t]+(?:sign|signs|note|letter|message|plaque|inscription|label|card|caption|headline|text|screen|banner|notice|display|words|writing|title|line|lines|placard|scroll|page|entry|telegram|email|e-mail|tag))"
        + "(?:[ \\t]+(?:also|then|just|never|only|quickly|slowly|silently|carefully|quietly|finally|again|once|first|already|eagerly|still|simply|merely|briefly|hastily|calmly))?[ \\t]+)([Rr])ead\\b(?![-'\u2019])");
    private static final java.util.regex.Pattern DOVE = java.util.regex.Pattern.compile(
        "(?<!\\b(?:The|This|That|A|An|One|Each|Every|My|His|Her|Their|Our|Your|Its|White|Grey|Gray|Mourning|Turtle|Rock|Little)[ \\t])(?<=\\b(?:[Hh]e|[Ss]he|[Ii]t|I|[Ww]e|[Tt]hey|[Yy]ou|then|and|instantly|immediately|quickly|[A-Z][a-z]{1,20})[ \\t])dove(?=[ \\t]+(?:into|in|under|for|off|through|after|at|behind|toward|towards|down|out|forward|headfirst|aside|over|across|between|beneath|below|onto|back|away|deep|deeper|straight|sideways|low)\\b)");
    // "The hawk dove into the water", "the boy dove beneath the waves": after "the/a/his... <noun>", only before words that
    // make it the verb (into, onto, headfirst...: "the dove in the cage" stays the bird). Kokoro lab 2026-11-03: dʌv -> dˈoʊv.
    private static final java.util.regex.Pattern DOVE_NOUN = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Tt]he|[Aa]|[Aa]n|[Hh]is|[Hh]er|[Tt]heir|[Mm]y|[Oo]ur|[Yy]our|[Ii]ts|[Tt]his|[Tt]hat)[ \\t][a-z]{2,15}[ \\t])(?<!\\b(?:white|grey|gray|mourning|turtle|rock|little|peace|stone|wooden|silver|golden|paper|ice|snow)[ \\t])dove(?=[ \\t]+(?:into|onto|headfirst|toward|towards|sideways|beneath|underneath|through|forward|deep|deeper|straight)\\b)");
    private static final java.util.regex.Pattern LEAD = java.util.regex.Pattern.compile(
        "\\b([Ll])ead(?=-(?:lined|grey|gray|coloured|colored|heavy|tipped|weighted|filled|sheathed)\\b|[ \\t]+(?:pipe|pipes|weight|weights|poisoning|bullet|bullets|shot|paint|ball|balls|sheet|sheets|pencil|pencils|crystal|mine|mines|ore|seal|seals|coffin|apron|plate|plates|slug|slugs|sinker|sinkers|lining|roof|roofs|pellet|pellets|ingot|ingots|casket|shielding|alloy|piping)\\b)"
        + "|(?<=\\b(?:[Mm]olten|[Ll]iquid|[Ss]olid|[Pp]ure|[Mm]elted|[Ss]heet|[Ww]hite|[Rr]ed)[ \\t])(l)ead\\b"
        + "|(?<=\\b(?:of|like|as)[ \\t])(l)ead(?=[ \\t]*(?:[.,;:!?\"\u201d)]|$)|[ \\t]+(?:and|or|that|which)\\b)");
    // "he moped around" ("mope-ed", the scooter) -> mohpt; "the sow grunted" ("so", the verb) -> sau (lab 2026-11-01)
    private static final java.util.regex.Pattern MOPED = java.util.regex.Pattern.compile(
        "(?<!\\b(?:The|This|That|A|An|His|Her|My|Your|Their|Our|Its|On|By)[ \\t])(?<=\\b(?:I|[Hh]e|[Ss]he|[Ww]e|[Tt]hey|[Yy]ou|[Ii]t|and|had|has|have|been|just|then|still|only|[A-Z][a-z]{1,20})[ \\t])moped\\b(?![-'’])");
    private static final java.util.regex.Pattern SOW = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Aa]|[Tt]he|[Oo]ld|[Ff]at|[Gg]reat|[Tt]hat|[Tt]his|[Hh]is|[Hh]er|[Tt]heir|[Mm]other|[Ww]ild|[Bb]reeding|[Pp]regnant|[Mm]uddy|[Bb]ig|[Hh]uge)[ \\t])sow(s?)\\b(?![-]|[ \\t]+(?:the|seeds?|them|it|a|an|discord|doubt|chaos|fear|dissent|confusion|terror|panic)\\b)");
    // "Don't desert me" / "to desert the army" ("dez-ert", the sand) -> dessert; "the minute details" / "a minute amount" ("minnit",
    // the time) -> my-nute; "the does ran" / "two does" ("duz") -> doze, the deer (lab 2026-11-01)
    private static final java.util.regex.Pattern DESERT = java.util.regex.Pattern.compile(
        "(?<=\\b(?:to|not|never|again|will|would|could|can|must|might|should|shall|I|you|we|they|[Dd]on't|[Dd]idn't|[Ww]on't|[Ww]ouldn't|[Cc]ouldn't|[Pp]lease|[Nn]ever)[ \\t])desert(?=[ \\t]+(?:me|us|him|her|them|you|it|the|his|their|our|my|your|its)\\b)");
    private static final java.util.regex.Pattern MINUTE = java.util.regex.Pattern.compile(
        "(?<!\\b(?i:last|eleventh|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty|thirty|forty|fifty|sixty|few|several|ninety)[ \\t-])(?<![\\d-][ \\t]?)\\b([Mm])inute(?=[ \\t]+(?:amounts?|details?|fraction|quantity|quantities|traces?|particles?|cracks?|adjustments?|differences?|variations?|specks?|droplets?|fragments?|grains?|examination|inspection|description|observations?|investigation|particulars|scrutiny|accuracy|precision)\\b)");
    private static final java.util.regex.Pattern DOES = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Tt]he|(?i:two|three|four|five|six|seven|eight|nine|ten|several|pregnant)|bucks and|stags and)[ \\t])does\\b(?![-'\u2019]|[ \\t]+(?:and[ \\t]+don|not|so|it|that|this)\\b)");
    // "a blazing row" / "had a row with him" (the quarrel; it says "roe") -> rau; "caught a bass", "sea bass", "bass fishing" (the
    // fish; it says "base") -> bas; "a pile of refuse", "the refuse heap" (rubbish; it says the verb "ri-fyooz") -> reffuce (lab 2026-11-01)
    private static final java.util.regex.Pattern ROW = java.util.regex.Pattern.compile(
        "(?<=\\b(?:blazing|furious|almighty|flaming|terrible|screaming|shouting|blistering|bitter|fierce|heated|dreadful|awful|stand-up|family)[ \\t])row\\b(?![-'\u2019]|[ \\t]+of\\b)"
        + "|(?<=\\b(?:[Hh]ad|[Hh]ave|[Hh]as|[Hh]aving)[ \\t]{1,3}(?:a|another)[ \\t])row(?=[ \\t]+(?:with|about|over)\\b)");
    private static final java.util.regex.Pattern BASS = java.util.regex.Pattern.compile(
        "(?<=\\b(?i:sea|striped|largemouth|smallmouth|rock|stone|peacock|chilean)[ \\t])bass\\b(?![-'\u2019])"
        + "|(?<=\\b(?:caught|catch|catches|catching|landed|hooked|grilled|fried|baked|roasted|smoked)[ \\t]{1,3}(?:(?:a|the|some|two|three|another|that|this)[ \\t]{1,3})?)bass\\b(?![-'\u2019]|[ \\t]+(?:guitar|player|line|drum|clef|voice|notes?)\\b)"
        + "|\\b([Bb])ass(?=[ \\t]+(?:fishing|boat|lure)\\b)");
    private static final java.util.regex.Pattern REFUSE = java.util.regex.Pattern.compile(
        "(?<=\\b(?:of|with|[Hh]ousehold|[Kk]itchen|[Gg]arden|[Ss]treet|[Hh]uman|rotting|stinking)[ \\t])refuse\\b(?![-'\u2019]|[ \\t]+(?:to|the|him|her|them|me|us|you|it|his|their|my|your|our|any|an?)\\b)"
        + "|\\b([Rr])efuse(?=[ \\t]+(?:heaps?|piles?|dumps?|collectors?|collection|bins?|tips?|pits?|sacks?|bags?|lorry|lorries|trucks?|carts?|cans?|disposal)\\b)");
    // "insult(s)" the verb it always says as the noun ("IN-sult"), wherever it is ("Don't insult me", "she will insult him", "he
    // insults us"): -> in-sult(s) before an object, not after a determiner ("the insult", "an insult to") (lab 2026-11-01)
    private static final java.util.regex.Pattern INSULT = java.util.regex.Pattern.compile(
        "(?<!\\b(?:[Tt]he|[Aa]n?|[Hh]is|[Hh]er|[Ii]ts|[Tt]heir|[Mm]y|[Yy]our|[Oo]ur|[Tt]his|[Tt]hat|[Nn]o|[Aa]ny|[Ee]very|[Ss]uch|[Ff]inal|[Gg]reat|[Gg]rave|[Dd]eadly|[Pp]ersonal|[Oo]ne|[Ww]orst|[Ll]ast)[ \\t])"
        + "\\b([Ii])nsult(s?)(?=[ \\t]+(?:the|a|an|your|my|his|her|their|our|its|it|them|him|me|us|you|this|that|these|those|every|everyone|anyone|people|yourself|myself|himself|herself|themselves|ourselves)\\b)");
    // "subject" the verb it always says as the noun ("SUB-ject"), even after "will"/"to"/"don't" ("they will subject him to tests",
    // "he subjects them to it"), and "subjected"/"subjecting" too: -> sub-ject (lab 2026-11-01). Before an object only, not after a
    // determiner ("the subject the class chose"); "subject to the law" (the adjective) stays.
    private static final java.util.regex.Pattern SUBJECT = java.util.regex.Pattern.compile(
        "(?<!\\b(?:[Tt]he|[Aa]|[Hh]is|[Hh]er|[Ii]ts|[Tt]heir|[Mm]y|[Yy]our|[Oo]ur|[Tt]his|[Tt]hat|[Nn]o|[Aa]ny|[Ee]very|[Ee]ach|[Ss]uch|[Ll]oyal|[Ff]avou?rite|[Nn]ew|[Ss]ame|[Oo]ne|[Tt]est|[Ff]irst|[Ll]ast)[ \\t])"
        + "\\b([Ss])ubject(s?)(?=[ \\t]+(?:it|them|him|me|us|you|yourself|myself|himself|herself|themselves|ourselves|itself|everyone|anyone|(?:the|his|her|their|its|our|your|my|these|those|all|every)[ \\t]+\\p{L}+(?:[ \\t]+\\p{L}+)?)[ \\t]+to\\b)"
        + "|\\b([Ss])ubject(ed|ing)\\b");
    // "learned" the adjective it says as the verb ("lurnd"): "a learned man", "the most learned scholar", "very learned" -> lernid
    // (lab 2026-11-01); not "a learned behaviour/skill/response" (said "lurnd").
    private static final java.util.regex.Pattern LEARNED = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Aa]n?|[Tt]he|[Mm]ost|[Vv]ery|[Hh]ighly|[Tt]ruly|[Mm]ore|[Ll]ess)[ \\t])learned\\b(?![-'\u2019]|[ \\t]+(?:behaviou?rs?|responses?|skills?|helplessness|traits?|habits?|reflex(?:es)?|to|the|that|how)\\b)");
    // "resume" the noun it says as the verb ("ri-ZOOM"): "his resume", "an updated resume", "the resumes piled up" -> résumé, which
    // it says right (lab 2026-11-02: ɹˈɛzuːmˌeɪ); not "her resume" ("let her resume her work"), "to resume", "the talks resume".
    private static final java.util.regex.Pattern RESUME = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Aa]|[Tt]he|[Hh]is|[Mm]y|[Yy]our|[Tt]heir|[Oo]ur|[Tt]his|[Tt]hat|[Nn]ew|[Uu]pdated|[Ii]mpressive|[Pp]erfect|[Ss]hort|[Pp]added|[Ff]ake|[Oo]ld|[Ff]irst)[ \\t])resume(s?)\\b(?![-'\u2019])");
    // "rebel against" the verb it says as the noun ("REB-el") after a plural noun or at a sentence start ("the peasants rebel against
    // the lord", "Rebel against them!"): -> rebell (lab 2026-11-01: ɹᵻbˈɛl); not after a determiner or an adjective ("a rebel against
    // society", "a lone rebel against the empire").
    private static final java.util.regex.Pattern REBEL = java.util.regex.Pattern.compile(
        "(?<!\\b(?:[Aa]n?|[Tt]he|[Hh]is|[Hh]er|[Ii]ts|[Tt]heir|[Mm]y|[Yy]our|[Oo]ur|[Tt]his|[Tt]hat|[Nn]o|[Ee]very|[Ee]ach|[Ss]uch|[Ll]one|[Yy]oung|[Tt]rue|[Ff]ellow|[Bb]orn|[Nn]atural|[Rr]eal|[Ff]amous|[Ff]ormer|[Ll]ast|[Oo]ne|[Gg]reat)[ \\t])"
        + "\\b([Rr])ebel(?=[ \\t]+against\\b)");
    // and "rebel" after a plural subject anywhere ("the slaves rebel at night", "the rebels rebel.", "Rebels rebel."): "REB-el" too
    // (lab 2026-11-02); after "will/to/they/might" it's right already. Not before the noun it describes ("the villages rebel leaders").
    private static final java.util.regex.Pattern REBEL_PL = java.util.regex.Pattern.compile(
        "(?<=(?:\\b(?:[Tt]he|[Tt]hese|[Tt]hose|[Hh]is|[Hh]er|[Tt]heir|[Oo]ur|[Mm]any|[Ss]ome|[Aa]ll|[Mm]ost)[ \\t]\\p{Ll}{1,19}[a-rt-z]s|(?:^|[.!?][ \\t])[A-Z]\\p{Ll}{1,19}[a-rt-z]s)[ \\t])"
        + "rebel(?![-'\u2019\\p{L}]|[ \\t]+(?:leaders?|army|armies|forces?|camps?|bases?|fleets?|troops|soldiers|fighters|groups?|factions?|movements?|cause|alliance|scum|ships?|strongholds?|territory|held|yell|spirit|side|heart|lines?|units?|commanders?|generals?|ranks|uprising|cells?|spies|spy|flags?|banners?)\\b)");
    // "produce" the noun ("fresh produce", "baskets of produce", "Produce was scarce") it says as the verb ("pruh-DOOSE") after an
    // adjective or "of", or at a sentence start: -> prodoose (lab 2026-11-02: pɹˈɑːduːs, as it says "the produce"). Not after a
    // determiner ("let her produce it": it says "the/his produce" right anyway).
    private static final java.util.regex.Pattern PRODUCE = java.util.regex.Pattern.compile(
        "(?<=\\b(?:of|[Ff]resh|[Ll]ocal|[Ff]arm|[Oo]rganic|[Ss]easonal|[Dd]airy|[Aa]gricultural|[Gg]arden|[Ss]urplus|[Rr]otten|[Rr]otting|[Ss]poiled|[Ee]xcess|[Ee]xotic|[Ii]mported|[Rr]aw|[Cc]heap|[Ss]pare|[Ll]eftover|[Hh]omegrown|[Hh]ome-grown|[Hh]arvested|[Bb]ruised|[Ww]ilted|[Ff]rozen|[Cc]anned|[Vv]illage|[Mm]arket)[ \\t])produce\\b(?![-'\u2019])"
        + "|(?:^|(?<=[.!?\"\u201c][ \\t]))Produce(?=[ \\t]+(?:was|is|were|are|had|has|from|prices|stalls?|sellers?|carts?)\\b)");
    // "a tear in the fabric", "a tear in his shirt" (the rip) it says "teer", as crying -> tare (lab 2026-11-02: tˈɛɹ). Not "a tear in her
    // eye" / "a tear in the corner of his eye" (crying: anything with "eye" in the sentence after it stays).
    private static final java.util.regex.Pattern TEAR_RIP = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Aa]|[Tt]he|[Ll]ong|[Ss]mall|[Tt]iny|[Ll]arge|[Hh]uge|[Jj]agged|[Rr]agged|[Ss]light|[Nn]asty|[Bb]ig|[Ww]ide)[ \\t])tear(s?)"
        + "(?=[ \\t]+in[ \\t]+(?:the|his|her|their|my|your|its|our|a|an)[ \\t]+\\p{L})(?![^.!?\\n]{0,60}\\b(?:eyes?|cheeks?|voice|lashes)\\b)");
    // "object to" after a plural subject ("the villagers object to the plan", "Many object to it") it says as the noun ("OB-ject"):
    // -> objekt (lab 2026-11-02: ɑːbdʒˈɛkt, as it says "will object to")
    private static final java.util.regex.Pattern OBJECT_TO = java.util.regex.Pattern.compile(
        "(?<=\\b(?:(?:[Tt]he|[Tt]hese|[Tt]hose|[Hh]is|[Hh]er|[Tt]heir|[Oo]ur|[Mm]y|[Yy]our|[Mm]any|[Ss]ome|[Aa]ll|[Bb]oth|[Mm]ost|[Ff]ew|[Tt]wo|[Tt]hree)[ \\t]\\p{Ll}{1,19}[a-rt-z]s"
        + "|[A-Z]\\p{Ll}{1,19}[a-rt-z]s|[Mm]any|[Ff]ew|[Bb]oth|[Oo]thers|[Pp]eople)[ \\t])object(?=[ \\t]+to\\b)");
    // "articulate" the adjective it says as the verb ("-late"): "she is articulate", "an articulate speaker", "inarticulate rage" -> articulit
    // (lab 2026-11-02: ɑːɹtˈɪkjʊlˌɪt); after "to/not/could/and" it's the verb and stays.
    private static final java.util.regex.Pattern ARTICULATE = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Aa]n|[Ii]s|[Ww]as|[Aa]re|[Ww]ere|[Bb]e|[Bb]een|[Vv]ery|[Ss]o|[Tt]oo|[Mm]ore|[Mm]ost|[Qq]uite|[Hh]ighly|[Rr]emarkably|[Ss]urprisingly|[Ee]xtremely|[Uu]nusually|[Pp]erfectly|[Ll]ess|[Aa]s|[Ss]eemed|[Ss]ounded)[ \\t])articulate(?![-'\u2019\\p{L}])"
        + "|\\b([Ii])narticulate(?![-'\u2019\\p{L}])");
    // "intimate" the verb (to hint) it says as the adjective ("-mit"): "to intimate that", "she intimates that" -> intimayt (lab 2026-11-02:
    // ˈɪntɪmˌeɪt); only before "that" after a verb word, so "so intimate that" and "intimate friends" stay.
    private static final java.util.regex.Pattern INTIMATE = java.util.regex.Pattern.compile(
        "(?<=\\b(?:to|not|would|could|should|might|did|may|will|must|[Hh]e|[Ss]he|[Ii]t|[Tt]his|[Ww]hich|[Ww]ho)[ \\t])intimate(s?)(?=[ \\t]+that\\b)");
    // "content" the adjective (happy) it says as the noun ("CON-tent") after most words but "is/was/be": "I'm content", "I am content to
    // wait", "he felt content", "she seemed more content" -> contennt (lab 2026-11-02: kəntˈɛnt). Only after those verbs (and an adverb),
    // so "more content coming soon", "the content of the letter" stay.
    private static final java.util.regex.Pattern CONTENT = java.util.regex.Pattern.compile(
        "(?<=(?:\\b(?:am|felt|feel|feels|feeling|seemed|seem|seems|looked|look|looks|looking|remained|remain|remains|stayed|grew)|['\u2019](?:m|re))"
        + "(?:[ \\t](?:not|quite|perfectly|entirely|fairly|rather|very|so|more|almost|oddly|strangely|completely|mostly|reasonably))?[ \\t])content(?![-'\u2019\\p{L}])");
    // "associate" / "advocate" the noun it says as the verb ("-ate"): "an associate of mine", "his associates", "a fierce advocate for them"
    // -> associat / advokit (lab 2026-11-02: ɐsˈoʊʃət, ˈædvəkˌɪt). Only after a determiner (or an adjective after one): "they advocate
    // change", "I associate it with fear" stay.
    private static final java.util.regex.Pattern ASSOC_ADV = java.util.regex.Pattern.compile(
        "(?<=\\b(?:[Aa]n?|[Tt]he|[Hh]is|[Hh]er|[Mm]y|[Yy]our|[Tt]heir|[Oo]ur|[Ii]ts|[Tt]his|[Tt]hat|[Tt]hese|[Tt]hose|[Ff]ormer|[Cc]lose|[Tt]rusted|[Kk]nown|"
        + "[Bb]usiness|[Ss]enior|[Jj]unior|[Oo]ld|[Ff]ierce|[Ss]taunch|[Ss]trong|[Pp]assionate|[Vv]ocal|[Tt]ireless|[Cc]hief|[Dd]evil['\u2019]s)[ \\t])"
        + "(associate|advocate)(s?)(?![-'\u2019\\p{L}]|[ \\t]+(?:with|it|him|her|them|themselves|himself|herself)\\b)");
    // "had to" the voice runs together ("hadtuh"), and then takes the verb after it for the noun: "had to record it" ("RECK-ord"),
    // "had to use it" ("yooss"), "had to live with it" ("lyve"), "had to present"... "hadd to" it says apart, and the verb right
    // (lab 2026-11-01: 30 verbs; "have to", "has to", "used to" are fine). "has to read" it says "red" -> reed.
    private static final java.util.regex.Pattern HAD_TO = java.util.regex.Pattern.compile("\\b([Hh])ad([ \\t]+)to(?=[ \\t]+\\p{Ll})");
    private static final java.util.regex.Pattern HAS_TO_READ = java.util.regex.Pattern.compile("\\b([Hh]as[ \\t]+to[ \\t]+)read\\b(?![-'\u2019])");
    private static String heteronyms(String t) {
        if (t.contains("ad to")) t = HAD_TO.matcher(t).replaceAll("$1add$2to");
        if (t.contains("as to read")) t = HAS_TO_READ.matcher(t).replaceAll("$1reed");
        if (t.contains("lose")) t = CLOSE.matcher(t).replaceAll("$1loze");
        if (t.contains("lose")) {
            java.util.regex.Matcher m = CLOSE_INTR.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, m.group(1) != null ? m.group(1) + "loze" : "cloze");
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("xcuse")) t = EXCUSE.matcher(t).replaceAll("$1xcuze");
        {
            java.util.regex.Matcher m = START_VERB.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) {
                String w = m.group(2);
                String r = START_SAID.get(w);
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(m.group(1) + r));
            }
            m.appendTail(b);
            t = b.toString();
            t = AND_USE.matcher(t).replaceAll("uze");
            m = PL_VERB.matcher(t);
            b = new StringBuffer();
            while (m.find()) {
                String r = START_SAID.get(Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1));
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(Character.toLowerCase(r.charAt(0)) + r.substring(1)));
            }
            m.appendTail(b);
            t = b.toString();
            m = PRE_VERB.matcher(t);
            b = new StringBuffer();
            while (m.find()) {
                String r = START_SAID.get(Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1));
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(Character.toLowerCase(r.charAt(0)) + r.substring(1)));
            }
            m.appendTail(b);
            t = b.toString();
            m = ADV_VERB.matcher(t);
            b = new StringBuffer();
            while (m.find()) {
                String r = START_SAID.get(Character.toUpperCase(m.group(2).charAt(0)) + m.group(2).substring(1));
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(m.group(1) + Character.toLowerCase(r.charAt(0)) + r.substring(1)));
            }
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("ead")) {
            t = READ.matcher(t).replaceAll("$1$2ed");
            t = LEAD.matcher(t).replaceAll("$1$2$3ed");
        }
        if (t.contains("dove")) t = DOVE_NOUN.matcher(DOVE.matcher(t).replaceAll("dohv")).replaceAll("dohv");
        if (t.contains("desert")) t = DESERT.matcher(t).replaceAll("dessert");
        if (t.contains("inute")) t = MINUTE.matcher(t).replaceAll("$1y-nute");
        if (t.contains("does")) t = DOES.matcher(t).replaceAll("doze");
        if (t.contains("nsult")) t = INSULT.matcher(t).replaceAll("$1n-sult$2");
        if (t.contains("learned")) t = LEARNED.matcher(t).replaceAll("lernid");
        if (t.contains("ebel against")) t = REBEL.matcher(t).replaceAll("$1ebell");
        if (t.contains("object to")) t = OBJECT_TO.matcher(t).replaceAll("objekt");
        if (t.contains("rticulate")) {
            java.util.regex.Matcher m = ARTICULATE.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, m.group(1) != null ? m.group(1) + "narticulit" : "articulit");
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("intimate")) t = INTIMATE.matcher(t).replaceAll("intimayt$1");
        if (t.contains("content")) t = CONTENT.matcher(t).replaceAll("contennt");
        if (t.contains("ate")) {
            java.util.regex.Matcher m = ASSOC_ADV.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, (m.group(1).equals("associate") ? "associat" : "advokit") + m.group(2));
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("tear")) t = TEAR_RIP.matcher(t).replaceAll("tare$1");
        if (t.contains("s rebel")) t = REBEL_PL.matcher(t).replaceAll("rebell");
        if (t.contains("roduce")) {
            java.util.regex.Matcher m = PRODUCE.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, m.group().charAt(0) == 'P' ? "Prodoose" : "prodoose");
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("resume")) t = RESUME.matcher(t).replaceAll("r\u00e9sum\u00e9$1");
        if (t.contains("ubject")) {
            java.util.regex.Matcher m = SUBJECT.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, m.group(1) != null ? m.group(1) + "ub-ject" + m.group(2) : m.group(3) + "ub-ject" + m.group(4));
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("row")) t = ROW.matcher(t).replaceAll("rau");
        if (t.contains("ass")) {
            java.util.regex.Matcher m = BASS.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, (m.group(1) != null ? m.group(1) : "b") + "as");
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("efuse")) {
            java.util.regex.Matcher m = REFUSE.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, (m.group(1) != null ? m.group(1) : "r") + "effuce");
            m.appendTail(b);
            t = b.toString();
        }
        if (t.contains("moped")) t = MOPED.matcher(t).replaceAll("mohpt");
        if (t.contains("sow")) t = SOW.matcher(t).replaceAll("sau$1").replaceAll("\\bsaus\\b", "sauz");
        if (t.contains("wind")) t = WIND.matcher(t).replaceAll("wined");
        if (t.contains("wound")) t = WOUND.matcher(t).replaceAll("wownd");
        if (t.contains("ow")) {
            java.util.regex.Matcher m = BOW.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) {
                String r;
                if (m.group(1) != null || m.group(2) != null) r = (m.group(1) != null ? m.group(1) : m.group(2)) + "bough" + m.group(3);
                else if (m.group(4) != null) r = "bough" + m.group(4);
                else r = "Bough";
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(r));
            }
            m.appendTail(b);
            t = b.toString();
        }
        return t;
    }
    static String forKokoro(String t) {
        t = heteronyms(t);
        if (t.indexOf('A') >= 0) t = LETTER_A.matcher(t).replaceAll("Eigh");
        // "Mm-hmm" it spells out ("M M hmm"); "Um-hmm" it says as the sound
        if (t.contains("m-hmm")) t = t.replace("Mm-hmm", "Um-hmm").replace("mm-hmm", "um-hmm");
        return t;
    }
    static final class Chunk {
        final int block; final String text; final String say;
        Chunk(int block, String text, String say) { this.block = block; this.text = text; this.say = say; }
    }

    final String url, site, title, fiction, nextUrl, prevUrl;
    // (Kindle "chapters" grow as pages are turned while reading, so this must be safe to add to)
    final List<Chunk> chunks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A chapter made in the app (the Kindle reader). */
    Chapter(String url, String site, String title, String fiction, List<Chunk> chunks) {
        this.url = url; this.site = site; this.title = title; this.fiction = fiction;
        this.nextUrl = null; this.prevUrl = null;
        this.chunks.addAll(chunks);
    }

    /** The same page, give or take a #part or a trailing slash. */
    static boolean samePage(String a, String b) {
        if (a == null || b == null || a.isEmpty()) return false;
        return bare(a).equals(bare(b));
    }
    private static String bare(String u) {
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    Chapter(JSONObject o) throws Exception {
        url = o.optString("url");
        site = o.optString("site");
        title = o.optString("title");
        fiction = o.optString("fiction");
        // a "next" link back to this same page (some sites, on the latest chapter) would read it again and again
        String n = o.isNull("nextUrl") ? null : o.optString("nextUrl", null), p = o.isNull("prevUrl") ? null : o.optString("prevUrl", null);
        nextUrl = samePage(n, url) ? null : n;
        prevUrl = samePage(p, url) ? null : p;
        JSONArray a = o.getJSONArray("chunks");
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.getJSONObject(i);
            chunks.add(new Chunk(c.optInt("block", -1), c.optString("text"), c.optString("say", c.optString("text"))));
        }
    }

    int size() { return chunks.size(); }

    /** The same shape the page script hands over (so it can be kept on the phone and read back). */
    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("url", url).put("site", site).put("title", title).put("fiction", fiction);
        o.put("nextUrl", nextUrl == null ? JSONObject.NULL : nextUrl).put("prevUrl", prevUrl == null ? JSONObject.NULL : prevUrl);
        JSONArray a = new JSONArray();
        for (Chunk c : chunks) a.put(new JSONObject().put("block", c.block).put("text", c.text).put("say", c.say));
        o.put("chunks", a);
        return o;
    }

    /**
     * The first sentence of the paragraph with this text (the one numbered near `block` if several
     * match), or of paragraph number `block` if the text isn't found. -1 if neither.
     */
    int firstOfParagraph(String para, int block) {
        String p = squash(para);
        int best = -1, bestD = Integer.MAX_VALUE;
        if (!p.isEmpty()) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                if (c.block < 0 || (i > 0 && chunks.get(i - 1).block == c.block)) continue;     // (paragraph starts only)
                String t = squash(c.text);
                if (t.isEmpty() || !p.startsWith(t.substring(0, Math.min(t.length(), 40)))) continue;
                int d = Math.abs(c.block - block);
                if (d < bestD) { bestD = d; best = i; }
            }
        }
        if (best >= 0) return best;
        for (int i = 0; i < chunks.size(); i++) if (chunks.get(i).block == block) return i;
        return -1;
    }
    private static String squash(String s) { return s == null ? "" : s.replaceAll("[\\s\u200b-\u200d\ufeff]+", ""); }

    /** Non-space characters of this paragraph that come before chunk i (to find it on the page). */
    int offsetInBlock(int i) {
        int n = 0;
        Chunk c = chunks.get(i);
        for (int k = i - 1; k >= 0 && chunks.get(k).block == c.block; k--) {
            String t = chunks.get(k).text.replaceAll("\\s+", "");
            n += t.codePointCount(0, t.length());                              // (the page script counts an emoji as one place)
        }
        return n;
    }

    /**
     * A chapter that says it's at the page read just before (lastPage), while another address is being loaded: an answer that
     * came late from the page before, not this one's (ChapterFetcher). A page that sends you on (a redirect) says its new
     * address, which isn't the last one, so it still counts.
     */
    static boolean fromPageBefore(String pageUrl, String lastPage, String loading) {
        if (pageUrl == null || pageUrl.isEmpty() || lastPage == null || loading == null) return false;
        return pageUrl.equals(lastPage) && !pageUrl.equals(loading);
    }

    /** Rough minutes left at a given speed (about 15 characters a second at 1x). */
    int minutesLeft(int pos, float speed) {
        long chars = 0;
        for (int i = Math.max(0, pos); i < chunks.size(); i++) chars += chunks.get(i).text.length();
        return (int) Math.max(1, Math.round(chars / (15.0 * speed) / 60.0));
    }
}
