class BossCliEngine:
    def status(self):
        return {"authenticated": False}

    def search(self, query):
        return {"jobs": []}
