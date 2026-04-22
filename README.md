# Controlart_Relays
Relays cabeados da Controlart

O driver vai detectar as 10 saídas (relays).
O driver vai criar 12 botões no device (seriam os inputs).

AO mesmo tempo, vai colocar um evento "pushed" com o numero de Input apertado no módulo. 

Exemplo: Input 1 apertado -> Pushed = 1

<img width="705" height="247" alt="image" src="https://github.com/user-attachments/assets/2fd3f4e1-34ca-4c03-a39b-67b438d244e1" />

Com isso, é possível criar uma regra no Rule Machine para quando:

Button--> Módulo ControlArt X --> Pushed --> NUMERO DO INPUT.


Fazer alguma ação no Rule Machine. 


<img width="1711" height="410" alt="image" src="https://github.com/user-attachments/assets/377914da-5a62-4b9d-90ff-c83afd9d07bb" />




<img width="1695" height="674" alt="image" src="https://github.com/user-attachments/assets/96e888bc-4d36-4a56-8035-f3ec42042741" />
