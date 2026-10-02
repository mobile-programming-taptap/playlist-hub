"""Fetch only CC0 recording facts; editorial tags below are written for TapTap.

Run explicitly: python3 tools/verify_catalog.py (MusicBrainz: one request/second).
Never imports Spotify/YouTube metadata into the recommendation catalogue.
"""
import datetime
import json
from pathlib import Path
import time
import unicodedata
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SEEDS = [
    ('Dynamite', 'BTS', 'en', 'bright,energetic,pop,workout', 'gdZLi9oWNZg'),
    ('Butter', 'BTS', 'en', 'bright,energetic,pop,workout', 'WMweEpGlu_U'),
    ('Spring Day', 'BTS', 'ko', 'calm,sad,kpop,rain,night', 'xEeFrLSkMm8'),
    ('Life Goes On', 'BTS', 'ko', 'calm,warm,kpop,night', 'Gj7v6xSAHrc'),
    ('Palette', 'IU', 'ko', 'calm,warm,kpop,study', 'd9IxdwEFk1c'),
    ('Through the Night', 'IU', 'ko', 'calm,acoustic,ballad,rain,night', 'BzYnNdJhZQw'),
    ('eight', 'IU', 'ko', 'bright,warm,kpop,drive', 'TgOu00Mf3kI'),
    ('Blueming', 'IU', 'ko', 'bright,energetic,kpop,drive', 'D1PvIWdJ8xo'),
    ('Love poem', 'IU', 'ko', 'calm,sad,ballad,rain,night', 'mUg2d05cnG0'),
    ('Celebrity', 'IU', 'ko', 'bright,kpop,warm,drive', '0-q1KafFCLU'),
    ('How You Like That', 'BLACKPINK', 'ko', 'energetic,kpop,workout', 'ioNng23DkIM'),
    ('Lovesick Girls', 'BLACKPINK', 'ko', 'energetic,kpop,drive', 'dyRsYk0LyA8'),
    ('Ditto', 'NewJeans', 'ko', 'calm,warm,kpop,rain', ''),
    ('Hype Boy', 'NewJeans', 'ko', 'bright,energetic,kpop,drive', ''),
    ('Super Shy', 'NewJeans', 'ko', 'bright,energetic,kpop,workout', 'ArmDp-zijuc'),
    ('Time of Our Life', 'DAY6', 'ko', 'bright,energetic,rock,drive', ''),
    ('You Were Beautiful', 'DAY6', 'ko', 'sad,rock,ballad,rain', ''),
    ('Yellow', 'Coldplay', 'en', 'calm,warm,rock,rain,night', 'yKNxeF4KMsY'),
    ('Fix You', 'Coldplay', 'en', 'calm,sad,rock,night', 'k4V3Mo61fJM'),
    ('Viva la Vida', 'Coldplay', 'en', 'bright,energetic,pop,drive', 'dvgZkm1xWPE'),
    ('The Scientist', 'Coldplay', 'en', 'calm,sad,ballad,rain,night', 'RB-RcX5DS5A'),
    ('Shake It Off', 'Taylor Swift', 'en', 'bright,energetic,pop,workout', 'nfWlot6h_JM'),
    ('Blank Space', 'Taylor Swift', 'en', 'energetic,pop,drive', 'e-ORhEE9VVg'),
    ('Lover', 'Taylor Swift', 'en', 'calm,warm,pop,rain,night', '-BjZmE2gtdo'),
    ('Shape of You', 'Ed Sheeran', 'en', 'bright,energetic,pop,workout', 'JGwWNGJdvx8'),
    ('Perfect', 'Ed Sheeran', 'en', 'calm,warm,acoustic,ballad,night', '2Vv-BfVoq4g'),
    ('Thinking Out Loud', 'Ed Sheeran', 'en', 'calm,warm,acoustic,rain', 'lp-EO5I60KA'),
    ('Someone Like You', 'Adele', 'en', 'calm,sad,ballad,rain,night', 'hLQl3WQQoQ0'),
    ('Make You Feel My Love', 'Adele', 'en', 'calm,warm,ballad,rain', '0put0_a--Ng'),
    ('River Flows in You', 'Yiruma', 'none', 'calm,piano,instrumental,study,rain,night', ''),
    ('Kiss the Rain', 'Yiruma', 'none', 'calm,piano,instrumental,study,rain,night', ''),
    ('May Be', 'Yiruma', 'none', 'calm,piano,instrumental,study,night', ''),
    ('Love Me', 'Yiruma', 'none', 'calm,piano,instrumental,study,night', ''),
    ('Comptine d’un autre été: L’après-midi', 'Yann Tiersen', 'none', 'calm,piano,instrumental,study,rain', ''),
    ('Nuvole bianche', 'Ludovico Einaudi', 'none', 'calm,piano,instrumental,study,night', ''),
    ('Una mattina', 'Ludovico Einaudi', 'none', 'calm,piano,instrumental,study,rain', ''),
    ('Divenire', 'Ludovico Einaudi', 'none', 'calm,piano,instrumental,study,night', ''),
    ('Experience', 'Ludovico Einaudi', 'none', 'energetic,piano,instrumental,study,drive', ''),
    ('One Summer’s Day', 'Joe Hisaishi', 'none', 'calm,piano,instrumental,study,rain', ''),
    ('Summer', 'Joe Hisaishi', 'none', 'bright,piano,instrumental,study,drive', ''),
]
ALIASES = {'Spring Day': ['봄날'], 'Palette': ['팔레트'], 'Through the Night': ['밤편지'],
           'Time of Our Life': ['한 페이지가 될 수 있게'], 'You Were Beautiful': ['예뻤어'],
           'One Summer’s Day': ["Spirited Away: One Summer's Day"]}


def normalized(value):
    return ''.join(c for c in unicodedata.normalize('NFKC', value).casefold() if c.isalnum())


def correct_title(title, wanted):
    return normalized(title) in {normalized(s) for s in [wanted] + ALIASES.get(wanted, [])}


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'TapTapStudentPrototype/0.1 (public CC0 catalogue verification)'})
    with urllib.request.urlopen(request, timeout=25) as response:
        return json.load(response)


def main():
    destination = ROOT / 'catalog' / 'tracks.json'
    destination.parent.mkdir(exist_ok=True)
    existing = json.loads(destination.read_text()) if destination.exists() else []
    seen = {(t['seedTitle'], t['seedArtist']): t for t in existing}
    result = []
    for title, artist, language, tags, video in SEEDS:
        if (title, artist) in seen and correct_title(seen[(title, artist)]['title'], title):
            result.append(seen[(title, artist)])
            continue
        titles = [title] + ALIASES.get(title, [])
        query = '(' + ' OR '.join(f'recording:"{name}"' for name in titles) + f') AND artist:"{artist}"'
        try:
            data = fetch('https://musicbrainz.org/ws/2/recording/?' + urllib.parse.urlencode({'query': query, 'fmt': 'json', 'limit': 10}))
            candidates = [r for r in data.get('recordings', []) if correct_title(r['title'], title) and not r.get('video') and not any(word in r.get('disambiguation', '').lower() for word in ('live', 'remix', 'instrumental', 'karaoke', 'cover', 'atmos')) and normalized(artist) in normalized(' '.join(a.get('name', '') for a in r.get('artist-credit', []) if isinstance(a, dict)))]
            if not candidates:
                print('NEEDS REVIEW:', title, artist, flush=True)
                time.sleep(1.1)
                continue
            recording = candidates[0]
            # YouTube oEmbed is used only to validate a hand-picked playback link.
            # Its title, author and thumbnail are never stored or used for recommendations.
            if video:
                try:
                    fetch('https://www.youtube.com/oembed?' + urllib.parse.urlencode({'url': f'https://www.youtube.com/watch?v={video}', 'format': 'json'}))
                except Exception:
                    print('PLAYBACK LINK NOT VERIFIED:', title, flush=True)
                    video = ''
            track = dict(id=recording['id'], title=recording['title'], artist=''.join(a.get('name', '') + a.get('joinphrase', '') for a in recording['artist-credit'] if isinstance(a, dict)), language=language, tags=tags.split(','), youtubeVideoId=video, source='MusicBrainz', sourceUrl=f'https://musicbrainz.org/recording/{recording["id"]}', license='CC0-1.0', licenseReference='https://musicbrainz.org/doc/About/Data_License', tagsSource='TapTap editorial sample', aiUseAllowed=True, checkedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(), seedTitle=title, seedArtist=artist)
            result.append(track)
            destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
            print('VERIFIED:', track['title'], '/', track['artist'], flush=True)
        except Exception as error:
            print('FAILED:', title, type(error).__name__, flush=True)
        time.sleep(1.1)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'{len(result)} verified recordings saved.', flush=True)


if __name__ == '__main__':
    main()
