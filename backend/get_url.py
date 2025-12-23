
import urllib.request
import json

try:
    with urllib.request.urlopen('http://localhost:4040/api/tunnels') as response:
        data = json.loads(response.read().decode())
        public_url = data['tunnels'][0]['public_url']
        with open('clean_url.txt', 'w', encoding='utf-8') as f:
            f.write(public_url.strip())
        print("Done")
except Exception as e:
    print(f"Error: {e}")
