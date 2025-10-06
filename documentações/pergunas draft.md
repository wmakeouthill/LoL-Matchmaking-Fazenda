bom, agora os picks e bans ficaram fora de ordem, o json parece melhor, mas esta bem formatado? existe o objeto times, com time azul contendo os players do time azul dentro com seus atributos, e o time vermelho com seus players e atributos dentro? as acoes ja mapeadas no json ordem e tal, essa organizacao de objeto melhor? a ordem nao esta como e pra estar como disse anteriormente a ordem correta, que é  ORDEM CORRETA:

Fase 1 - Bans (6): Top Azul → Top Vermelho → Jungle Azul → Jungle Vermelho → Mid Azul → Mid Vermelho
Fase 2 - Picks (6): Azul pick 1 → Vermelho pick 2 → Azul pick 2 → Vermelho pick 1 → Azul pick 1 → (depois mais bans)
Fase 3 - Bans (4): ADC Azul → ADC Vermelho → Suporte Azul → Suporte Vermelho
Fase 4 - Picks (4): Vermelho pick 2 → Azul pick 2 → Vermelho Last Pick.

e ao confirmar meu campeao no modal de selecao de campeao, a acao nao contou, e o timer do draft ainda nao anda, nem no modal de selecao e nem na tela principal do draft voce devia varrer pra resolver de vez o porque o backend nao consegue atualizar o front, e veja o json pra confirmar o que eu disse:

formato json proposto:
{
  "teams": {
    "blue": {
      "name": "Blue Team",
      "averageMmr": 1712,
      "players": [
        {
          "summonerName": "Bot4",
          "mmr": 1754,
          "assignedLane": "top",
          "teamIndex": 0,
          "actions": [
            {
              "index": 0,
              "type": "ban",
              "championId": "240",
              "championName": "Kled",
              "phase": "ban1"
            },
            {
              "index": 6,
              "type": "pick",
              "championId": "64",
              "championName": "LeeSin",
              "phase": "pick1"
            }
          ]
        },
        {
          "summonerName": "FZD Ratoso#fzd",
          "mmr": 2101,
          "assignedLane": "jungle",
          "teamIndex": 1,
          "actions": [
            {
              "index": 15,
              "type": "ban",
              "championId": null,
              "championName": null,
              "phase": "ban2",
              "status": "pending"  // ✅ Sua vez agora!
            }
          ]
        }
        // ... resto dos players
      ]
    },
    "red": {
      "name": "Red Team",
      "averageMmr": 1203,
      "players": [...]
    }
  },
  "currentAction": 15,
  "currentPhase": "ban2",
  "currentPlayer": "FZD Ratoso#fzd",
  "currentTeam": "blue"
}

{"currentTeam":"blue","teams":{"red":{"players":[{"mmr":1301,"teamIndex":5,"summonerName":"Bot9","assignedLane":"top","actions":[{"phase":"ban2","championId":"45","championName":"Veigar","index":12,"type":"ban","status":"completed"}],"playerId":-9},{"mmr":1843,"teamIndex":6,"summonerName":"Bot1","assignedLane":"jungle","actions":[{"phase":"ban1","championId":"11","championName":"MasterYi","index":5,"type":"ban","status":"completed"},{"phase":"pick1","championId":"62","championName":"MonkeyKing","index":11,"type":"pick","status":"completed"}],"playerId":-1},{"mmr":1444,"teamIndex":7,"summonerName":"Bot6","assignedLane":"mid","actions":[{"phase":"ban1","championId":"96","championName":"KogMaw","index":1,"type":"ban","status":"completed"},{"phase":"pick1","championId":"420","championName":"Illaoi","index":7,"type":"pick","status":"completed"}],"playerId":-6},{"mmr":1450,"teamIndex":8,"summonerName":"Bot2","assignedLane":"bot","actions":[{"phase":"ban1","championId":"105","championName":"Fizz","index":3,"type":"ban","status":"completed"},{"phase":"pick1","championId":"106","championName":"Volibear","index":8,"type":"pick","status":"completed"}],"playerId":-2},{"mmr":1259,"teamIndex":9,"summonerName":"Bot8","assignedLane":"support","actions":[],"playerId":-8}],"name":"Red Team","teamNumber":2,"averageMmr":1459},"blue":{"players":[{"mmr":1699,"teamIndex":0,"summonerName":"Bot4","assignedLane":"top","actions":[{"phase":"ban1","championId":"150","championName":"Gnar","index":2,"type":"ban","status":"completed"},{"phase":"pick1","championId":"9","championName":"Fiddlesticks","index":9,"type":"pick","status":"completed"}],"playerId":-4},{"mmr":2101,"teamIndex":1,"summonerName":"FZD Ratoso#fzd","assignedLane":"jungle","actions":[],"playerId":1786097},{"mmr":1495,"teamIndex":2,"summonerName":"Bot5","assignedLane":"mid","actions":[{"phase":"ban1","championId":"104","championName":"Graves","index":0,"type":"ban","status":"completed"},{"phase":"pick1","championId":"5","championName":"XinZhao","index":6,"type":"pick","status":"completed"}],"playerId":-5},{"mmr":1706,"teamIndex":3,"summonerName":"Bot7","assignedLane":"bot","actions":[],"playerId":-7},{"mmr":1293,"teamIndex":4,"summonerName":"Bot3","assignedLane":"support","actions":[{"phase":"ban1","championId":"14","championName":"Sion","index":4,"type":"ban","status":"completed"},{"phase":"pick1","championId":"17","championName":"Teemo","index":10,"type":"pick","status":"completed"}],"playerId":-3}],"name":"Blue Team","teamNumber":1,"averageMmr":1659}},"currentPlayer":"FZD Ratoso#fzd","team1":[{"mmr":1699,"teamIndex":0,"primaryLane":"jungle","summonerName":"Bot4","assignedLane":"top","secondaryLane":"jungle","isAutofill":false,"playerId":-4},{"mmr":2101,"teamIndex":1,"primaryLane":"jungle","summonerName":"FZD Ratoso#fzd","assignedLane":"jungle","secondaryLane":"bot","isAutofill":false,"playerId":1786097},{"mmr":1495,"teamIndex":2,"primaryLane":"mid","summonerName":"Bot5","assignedLane":"mid","secondaryLane":"bot","isAutofill":false,"playerId":-5},{"mmr":1706,"teamIndex":3,"primaryLane":"bot","summonerName":"Bot7","assignedLane":"bot","secondaryLane":"jungle","isAutofill":false,"playerId":-7},{"mmr":1293,"teamIndex":4,"primaryLane":"bot","summonerName":"Bot3","assignedLane":"support","secondaryLane":"mid","isAutofill":false,"playerId":-3}],"currentPhase":"ban2","currentActionType":"ban","team2":[{"mmr":1301,"teamIndex":5,"primaryLane":"mid","summonerName":"Bot9","assignedLane":"top","secondaryLane":"mid","isAutofill":false,"playerId":-9},{"mmr":1843,"teamIndex":6,"primaryLane":"jungle","summonerName":"Bot1","assignedLane":"jungle","secondaryLane":"jungle","isAutofill":false,"playerId":-1},{"mmr":1444,"teamIndex":7,"primaryLane":"mid","summonerName":"Bot6","assignedLane":"mid","secondaryLane":"top","isAutofill":false,"playerId":-6},{"mmr":1450,"teamIndex":8,"primaryLane":"bot","summonerName":"Bot2","assignedLane":"bot","secondaryLane":"mid","isAutofill":false,"playerId":-2},{"mmr":1259,"teamIndex":9,"primaryLane":"bot","summonerName":"Bot8","assignedLane":"support","secondaryLane":"mid","isAutofill":false,"playerId":-8}],"currentIndex":13}


{"currentTeam":null,"teams":{"red":{"players":[{"mmr":1424,"teamIndex":5,"summonerName":"Bot4","assignedLane":"top","actions":[{"phase":"ban1","championId":"45","championName":"Veigar","index":1,"type":"ban","status":"completed"},{"phase":"pick1","championId":"18","championName":"Tristana","index":7,"type":"pick","status":"completed"}],"playerId":-4},{"mmr":1879,"teamIndex":6,"summonerName":"Bot2","assignedLane":"jungle","actions":[{"phase":"ban1","championId":"221","championName":"Zeri","index":3,"type":"ban","status":"completed"},{"phase":"pick1","championId":"40","championName":"Janna","index":8,"type":"pick","status":"completed"}],"playerId":-2},{"mmr":1306,"teamIndex":7,"summonerName":"Bot1","assignedLane":"mid","actions":[{"phase":"ban1","championId":"76","championName":"Nidalee","index":5,"type":"ban","status":"completed"},{"phase":"pick1","championId":"142","championName":"Zoe","index":11,"type":"pick","status":"completed"}],"playerId":-1},{"mmr":954,"teamIndex":8,"summonerName":"Bot3","assignedLane":"bot","actions":[{"phase":"ban2","championId":"6","championName":"Urgot","index":12,"type":"ban","status":"completed"},{"phase":"pick2","championId":"33","championName":"Rammus","index":16,"type":"pick","status":"completed"}],"playerId":-3},{"mmr":906,"teamIndex":9,"summonerName":"Bot6","assignedLane":"support","actions":[{"phase":"ban2","championId":"38","championName":"Kassadin","index":14,"type":"ban","status":"completed"},{"phase":"pick2","championId":"126","championName":"Jayce","index":19,"type":"pick","status":"completed"}],"playerId":-6}],"name":"Red Team","teamNumber":2,"averageMmr":1294},"blue":{"players":[{"mmr":1804,"teamIndex":0,"summonerName":"Bot5","assignedLane":"top","actions":[{"phase":"ban1","championId":"887","championName":"Gwen","index":0,"type":"ban","status":"completed"},{"phase":"pick1","championId":"420","championName":"Illaoi","index":6,"type":"pick","status":"completed"}],"playerId":-5},{"mmr":2101,"teamIndex":1,"summonerName":"FZD Ratoso#fzd","assignedLane":"jungle","actions":[{"phase":"ban1","championId":"103","championName":"Ahri","index":2,"type":"ban","status":"completed"},{"phase":"pick1","championId":"166","championName":"Akshan","index":9,"type":"pick","status":"completed"}],"playerId":1786097},{"mmr":1660,"teamIndex":2,"summonerName":"Bot8","assignedLane":"mid","actions":[{"phase":"ban1","championId":"902","championName":"Milio","index":4,"type":"ban","status":"completed"},{"phase":"pick1","championId":"3","championName":"Galio","index":10,"type":"pick","status":"completed"}],"playerId":-8},{"mmr":1253,"teamIndex":3,"summonerName":"Bot7","assignedLane":"bot","actions":[{"phase":"ban2","championId":"57","championName":"Maokai","index":13,"type":"ban","status":"completed"},{"phase":"pick2","championId":"147","championName":"Seraphine","index":17,"type":"pick","status":"completed"}],"playerId":-7},{"mmr":1548,"teamIndex":4,"summonerName":"Bot9","assignedLane":"support","actions":[{"phase":"ban2","championId":"121","championName":"Khazix","index":15,"type":"ban","status":"completed"},{"phase":"pick2","championId":"92","championName":"Riven","index":18,"type":"pick","status":"completed"}],"playerId":-9}],"name":"Blue Team","teamNumber":1,"averageMmr":1673}},"currentPlayer":null,"team1":[{"mmr":1804,"teamIndex":0,"primaryLane":"jungle","summonerName":"Bot5","assignedLane":"top","secondaryLane":"jungle","isAutofill":false,"playerId":-5},{"mmr":2101,"teamIndex":1,"primaryLane":"jungle","summonerName":"FZD Ratoso#fzd","assignedLane":"jungle","secondaryLane":"bot","isAutofill":false,"playerId":1786097},{"mmr":1660,"teamIndex":2,"primaryLane":"mid","summonerName":"Bot8","assignedLane":"mid","secondaryLane":"bot","isAutofill":false,"playerId":-8},{"mmr":1253,"teamIndex":3,"primaryLane":"top","summonerName":"Bot7","assignedLane":"bot","secondaryLane":"mid","isAutofill":false,"playerId":-7},{"mmr":1548,"teamIndex":4,"primaryLane":"support","summonerName":"Bot9","assignedLane":"support","secondaryLane":"mid","isAutofill":false,"playerId":-9}],"currentPhase":"completed","currentActionType":null,"team2":[{"mmr":1424,"teamIndex":5,"primaryLane":"top","summonerName":"Bot4","assignedLane":"top","secondaryLane":"jungle","isAutofill":false,"playerId":-4},{"mmr":1879,"teamIndex":6,"primaryLane":"jungle","summonerName":"Bot2","assignedLane":"jungle","secondaryLane":"jungle","isAutofill":false,"playerId":-2},{"mmr":1306,"teamIndex":7,"primaryLane":"jungle","summonerName":"Bot1","assignedLane":"mid","secondaryLane":"jungle","isAutofill":false,"playerId":-1},{"mmr":954,"teamIndex":8,"primaryLane":"jungle","summonerName":"Bot3","assignedLane":"bot","secondaryLane":"bot","isAutofill":false,"playerId":-3},{"mmr":906,"teamIndex":9,"primaryLane":"top","summonerName":"Bot6","assignedLane":"support","secondaryLane":"top","isAutofill":false,"playerId":-6}],"currentIndex":20}


{
  "currentTeam": null,
  "teams": {
    "red": {
      "players": [
        {
          "mmr": 1424,
          "teamIndex": 5,
          "summonerName": "Bot4",
          "assignedLane": "top",
          "actions": [
            {
              "phase": "ban1",
              "championId": "45",
              "championName": "Veigar",
              "index": 1,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick1",
              "championId": "18",
              "championName": "Tristana",
              "index": 7,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -4
        },
        {
          "mmr": 1879,
          "teamIndex": 6,
          "summonerName": "Bot2",
          "assignedLane": "jungle",
          "actions": [
            {
              "phase": "ban1",
              "championId": "221",
              "championName": "Zeri",
              "index": 3,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick1",
              "championId": "40",
              "championName": "Janna",
              "index": 8,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -2
        },
        {
          "mmr": 1306,
          "teamIndex": 7,
          "summonerName": "Bot1",
          "assignedLane": "mid",
          "actions": [
            {
              "phase": "ban1",
              "championId": "76",
              "championName": "Nidalee",
              "index": 5,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick1",
              "championId": "142",
              "championName": "Zoe",
              "index": 11,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -1
        },
        {
          "mmr": 954,
          "teamIndex": 8,
          "summonerName": "Bot3",
          "assignedLane": "bot",
          "actions": [
            {
              "phase": "ban2",
              "championId": "6",
              "championName": "Urgot",
              "index": 12,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick2",
              "championId": "33",
              "championName": "Rammus",
              "index": 16,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -3
        },
        {
          "mmr": 906,
          "teamIndex": 9,
          "summonerName": "Bot6",
          "assignedLane": "support",
          "actions": [
            {
              "phase": "ban2",
              "championId": "38",
              "championName": "Kassadin",
              "index": 14,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick2",
              "championId": "126",
              "championName": "Jayce",
              "index": 19,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -6
        }
      ],
      "name": "Red Team",
      "teamNumber": 2,
      "averageMmr": 1294
    },
    "blue": {
      "players": [
        {
          "mmr": 1804,
          "teamIndex": 0,
          "summonerName": "Bot5",
          "assignedLane": "top",
          "actions": [
            {
              "phase": "ban1",
              "championId": "887",
              "championName": "Gwen",
              "index": 0,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick1",
              "championId": "420",
              "championName": "Illaoi",
              "index": 6,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -5
        },
        {
          "mmr": 2101,
          "teamIndex": 1,
          "summonerName": "FZD Ratoso#fzd",
          "assignedLane": "jungle",
          "actions": [
            {
              "phase": "ban1",
              "championId": "103",
              "championName": "Ahri",
              "index": 2,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick1",
              "championId": "166",
              "championName": "Akshan",
              "index": 9,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": 1786097
        },
        {
          "mmr": 1660,
          "teamIndex": 2,
          "summonerName": "Bot8",
          "assignedLane": "mid",
          "actions": [
            {
              "phase": "ban1",
              "championId": "902",
              "championName": "Milio",
              "index": 4,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick1",
              "championId": "3",
              "championName": "Galio",
              "index": 10,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -8
        },
        {
          "mmr": 1253,
          "teamIndex": 3,
          "summonerName": "Bot7",
          "assignedLane": "bot",
          "actions": [
            {
              "phase": "ban2",
              "championId": "57",
              "championName": "Maokai",
              "index": 13,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick2",
              "championId": "147",
              "championName": "Seraphine",
              "index": 17,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -7
        },
        {
          "mmr": 1548,
          "teamIndex": 4,
          "summonerName": "Bot9",
          "assignedLane": "support",
          "actions": [
            {
              "phase": "ban2",
              "championId": "121",
              "championName": "Khazix",
              "index": 15,
              "type": "ban",
              "status": "completed"
            },
            {
              "phase": "pick2",
              "championId": "92",
              "championName": "Riven",
              "index": 18,
              "type": "pick",
              "status": "completed"
            }
          ],
          "playerId": -9
        }
      ],
      "name": "Blue Team",
      "teamNumber": 1,
      "averageMmr": 1673
    }
  },
  "currentPlayer": null,
  "team1": [
    {
      "mmr": 1804,
      "teamIndex": 0,
      "primaryLane": "jungle",
      "summonerName": "Bot5",
      "assignedLane": "top",
      "secondaryLane": "jungle",
      "isAutofill": false,
      "playerId": -5
    },
    {
      "mmr": 2101,
      "teamIndex": 1,
      "primaryLane": "jungle",
      "summonerName": "FZD Ratoso#fzd",
      "assignedLane": "jungle",
      "secondaryLane": "bot",
      "isAutofill": false,
      "playerId": 1786097
    },
    {
      "mmr": 1660,
      "teamIndex": 2,
      "primaryLane": "mid",
      "summonerName": "Bot8",
      "assignedLane": "mid",
      "secondaryLane": "bot",
      "isAutofill": false,
      "playerId": -8
    },
    {
      "mmr": 1253,
      "teamIndex": 3,
      "primaryLane": "top",
      "summonerName": "Bot7",
      "assignedLane": "bot",
      "secondaryLane": "mid",
      "isAutofill": false,
      "playerId": -7
    },
    {
      "mmr": 1548,
      "teamIndex": 4,
      "primaryLane": "support",
      "summonerName": "Bot9",
      "assignedLane": "support",
      "secondaryLane": "mid",
      "isAutofill": false,
      "playerId": -9
    }
  ],
  "currentPhase": "completed",
  "currentActionType": null,
  "team2": [
    {
      "mmr": 1424,
      "teamIndex": 5,
      "primaryLane": "top",
      "summonerName": "Bot4",
      "assignedLane": "top",
      "secondaryLane": "jungle",
      "isAutofill": false,
      "playerId": -4
    },
    {
      "mmr": 1879,
      "teamIndex": 6,
      "primaryLane": "jungle",
      "summonerName": "Bot2",
      "assignedLane": "jungle",
      "secondaryLane": "jungle",
      "isAutofill": false,
      "playerId": -2
    },
    {
      "mmr": 1306,
      "teamIndex": 7,
      "primaryLane": "jungle",
      "summonerName": "Bot1",
      "assignedLane": "mid",
      "secondaryLane": "jungle",
      "isAutofill": false,
      "playerId": -1
    },
    {
      "mmr": 954,
      "teamIndex": 8,
      "primaryLane": "jungle",
      "summonerName": "Bot3",
      "assignedLane": "bot",
      "secondaryLane": "bot",
      "isAutofill": false,
      "playerId": -3
    },
    {
      "mmr": 906,
      "teamIndex": 9,
      "primaryLane": "top",
      "summonerName": "Bot6",
      "assignedLane": "support",
      "secondaryLane": "top",
      "isAutofill": false,
      "playerId": -6
    }
  ],
  "currentIndex": 20
}