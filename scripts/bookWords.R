library(mmmTools)
library(dplyr)
library(ggplot2)

f = readFile('data/bookWords.txt')

f1 = gsub("\\r\\n\\r\\n",",", f)
f2 = gsub("\\r\\n",'', f1)

write(f2,
      file = 'data/bookWords.csv')


bookWords = read.csv('data/bookWords.csv',
                       header = FALSE)

bookWords = t(as.matrix(bookWords)) %>% 
  as.data.frame() %>% 
  rename(Length = V1)

bookWords %>% filter(Length >= 17500, Length <= 300000) %>% nrow()

t = bookWords %>% filter(Length >= 30000 , Length <= 150000)
mean(t$Length) / 300

g = ggplot(bookWords, aes(Length)) +
  geom_histogram(bins = 500, fill = 'steelblue') +
  geom_vline(xintercept = 17500) +
  geom_vline(xintercept = 300000) +
  theme_bw() +
  scale_x_continuous(labels = scales::'comma') +
  ylab('Books') +
  xlab('Words') +
  ggtitle("Word Count of All 4,160 Books",
          subtitle = 'Vertical Lines: 3,207 books have between 100 & 860 pages (30K - 150K words)')

ggsave(filename = 'plots/bookWordHist.png',
       plot = g,
       device = "png")

ggplot(bookWords %>% filter(Length > 500), aes(Length)) +
  geom_histogram(bins = 500, fill = 'steelblue') +
  theme_bw() +
  ylab('Number of Books')+
  xlab('Word Count') +
  ggtitle("3,943 Books with more than 300 Words")

ggplot(bookWords %>% filter(Length <= 25000), aes(Length)) +
  geom_histogram(bins = 500, fill = 'steelblue') +
  theme_bw() +
  ylab('Number of Books')

ggplot(bookWords %>% filter(Length <= 1000000 & Length > 300*100*5), aes(Length)) +
  geom_histogram(bins = 500, fill = 'steelblue') +
  theme_bw() +
  ylab('Number of Books')

ggplot(bookWords %>% filter(Length <= 20000), aes(Length)) +
  geom_histogram(bins = 500, fill = 'steelblue') +
  theme_bw() +
  ylab('Number of Books')
