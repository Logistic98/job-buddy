from pydantic import BaseModel

class BossCliConfig(BaseModel):
    home: str = ".run/boss-cli-home"
    max_search_page: int = 1
