#!/usr/bin/env python3
"""Builds app/src/main/assets/pinyin/english.txt, the English words pinyin mode can offer.

Takes the most frequent words of count_1w.txt (https://norvig.com/ngrams/) that are also in a
dictionary, which drops web junk, then removes romanised names and rude words and adds
trade terms and brands the corpus misses. One word per line, most frequent first, as displayed.

Usage: tools/pinyin/build_english.py count_1w.txt /usr/share/dict/words
"""
import sys

COUNT = 9500
MIN_LENGTH = 4

# Whole pinyin that the word list only has as surnames, places or loanwords.
ROMANISED = set("""
    bang bian biao bing chan chang chao chen cheng chin ching chou chung ding dong duan fang feng
    gang gong guan guang hang heng hong huan huang jiang jiao jing jian juan kang kong kuan lang
    liang liao ling ming nang ning pang peng ping qing quan rong sang shan shang shen sheng shin
    shou shuang tang teng tian ting tong wang weng xian xiang xiao xing yang ying yong yuan zhang
    zhao zhen zheng zhong zhou zhuang
    """.split())

BLOCKED = set("""
    anal anus arse ass asses bastard bitch bitches blowjob boob boobs bollocks bugger clit cock
    cocks crap cunt cunts dick dicks dildo dyke erotic erotica faggot fetish fuck fucked fucking fucks
    gangbang gays handjob hentai homo horny incest jerk kike milf nazi nazis negro nigger nude
    nudes nudity orgasm orgy penis piss porn porno pussy rape raped rapes rapist retard sadism
    scum semen sexy shit shits shitty slut sluts sperm spunk tits titty twat vagina wank whore
    whores xxxx
    """.split())

EXTRA = """
    iPhone iPad WhatsApp WeChat Facebook Instagram TikTok YouTube Google Gmail Amazon eBay PayPal
    Shopify AliExpress Walmart Temu Shein LinkedIn Telegram Twitter Excel PowerPoint Zoom
    Microsoft Apple Samsung Android Windows FedEx Costco invoice invoices shipping
    shipment shipments tracking inquiry inquiries quotation quotations refund refunds wholesale
    supplier suppliers customs freight warehouse logistics discount discounts coupon coupons
    voucher deposit payment payments deadline meeting schedule thanks please sorry okay hello
    email emails online website login logout password
    """.split()


def main(count_path, dictionary_path):
    dictionary = set()
    with open(dictionary_path, encoding="utf8") as f:
        for line in f:
            word = line.strip()
            if word.islower():
                dictionary.add(word)
    picked = []
    seen = set()
    with open(count_path, encoding="utf8") as f:
        for line in f:
            word = line.split("\t")[0].strip()
            if len(picked) >= COUNT:
                break
            if len(word) < MIN_LENGTH or not word.isascii() or not word.isalpha():
                continue
            if word in BLOCKED or word in ROMANISED or word not in dictionary:
                continue
            picked.append(word)
            seen.add(word)
    extras = [word for word in EXTRA if word.lower() not in seen or word.lower() != word]
    lowered = {word.lower() for word in extras}
    words = extras + [word for word in picked if word not in lowered]
    with open("app/src/main/assets/pinyin/english.txt", "w", encoding="utf8") as f:
        f.write("\n".join(words) + "\n")
    print(len(words), "words")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
